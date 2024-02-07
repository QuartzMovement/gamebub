package platform.handheld

import chisel3._
import chisel3.util._
import lib.mem.{MemoryArbiter, MemoryInterface, MemoryMap, RegisterMap}
import xilinx.xpm_cdc_handshake

object HandheldTop extends App {

  emitVerilog(new HandheldTop(
//    new HandheldGameboy
    new HandheldTester
  ), args)
}

/** IO bundle used for a handheld submodule. */
class HandheldIo extends Bundle {
  val enable = Input(Bool())

  val buttons = Input(new HandheldButtons)

  // Video output
  val framebufferX = Output(UInt(8.W))
  val framebufferY = Output(UInt(8.W))
  val framebufferDataR = Output(UInt(5.W))
  val framebufferDataG = Output(UInt(5.W))
  val framebufferDataB = Output(UInt(5.W))
  val framebufferWriteEnable = Output(Bool())

  // Audio output
  val audioLeft = Output(SInt(16.W))
  val audioRight = Output(SInt(16.W))

  // Vibration
  val vibrate = Output(Bool())

  // Cartridge
  val cartridgeEnabled = Output(Bool())
  val cartridge = new HandheldCartridge

  val link = new HandheldLink
  val pmod = new HandheldPmod

  val mcuInterface = new MemoryInterface(addressWidth = 30, dataWidth = 32)

  // Memory interfaces
  val sram = Flipped(new MemoryInterface(addressWidth = 19, dataWidth = 16))
  val sdram = Flipped(new MemoryInterface(addressWidth = 25, dataWidth = 32))
}

trait HandheldModule {
  def io: HandheldIo

  def framebufferW: Int
  def framebufferH: Int
}

/** Buttons on the handheld. All are active-high. */
class HandheldButtons extends Bundle {
  val a = Bool()
  val b = Bool()
  val x = Bool()
  val y = Bool()
  val up = Bool()
  val down = Bool()
  val left = Bool()
  val right = Bool()
  val l = Bool()
  val r = Bool()
  val start = Bool()
  val select = Bool()
}

/**
 * Cartridge I/O for the handheld.
 *
 * Bank 0: A16 to A23
 * Bank 1: AD8 to AD15
 * Bank 2: AD0 to AD7
 * Bank 3:
 *  0: nCS1
 *  1: nRD
 *  2: nWR
 *  3: PHI
 * Pin 30: nRST (GB) / nCS2 (GBA)
 * Pin 31: VIN (GB) / nIRQ (GBA)
 *
 * Directions are all 1 for output, 0 for input.
 */
class HandheldCartridge extends Bundle {
  val bank0In = Input(UInt(8.W))
  val bank1In = Input(UInt(8.W))
  val bank2In = Input(UInt(8.W))
  val bank3In = Input(UInt(4.W))
  val pin30In = Input(Bool())
  val pin31In = Input(Bool())

  val bank0Out = Output(UInt(8.W))
  val bank1Out = Output(UInt(8.W))
  val bank2Out = Output(UInt(8.W))
  val bank3Out = Output(UInt(4.W))
  val pin30Out = Output(Bool())
  val pin31Out = Output(Bool())

  val bank0Dir = Output(Bool())
  val bank1Dir = Output(Bool())
  val bank2Dir = Output(Bool())
  val bank3Dir = Output(Bool())
  val pin30Dir = Output(Bool())
  val pin31Dir = Output(Bool())
}

class HandheldPmod extends Bundle {
  val in = Input(UInt(4.W))
  val out = Output(UInt(4.W))
  val dir = Output(UInt(4.W))
}

class HandheldLink extends Bundle {
  val soIn = Input(Bool())
  val siIn = Input(Bool())
  val sdIn = Input(Bool())
  val scIn = Input(Bool())
  val soOut = Output(Bool())
  val siOut = Output(Bool())
  val sdOut = Output(Bool())
  val scOut = Output(Bool())
  val soDir = Output(Bool())
  val siDir = Output(Bool())
  val sdDir = Output(Bool())
  val scDir = Output(Bool())
}

/**
 * Top-level Chisel module for the handheld.
 *
 * The outer clock is passed down to the inner module,
 * e.g. 8.3886 MHz for Gameboy.
 */
class HandheldTop[T <: Module with HandheldModule](genT: => T) extends Module {
  val sdramConfig = SdramConfig()
  val io = IO(new Bundle {
    /** Audio/video clock: 12.288 MHz */
    val clock_av = Input(Clock())

    /** MCU interrupt: true to pull it low (active) */
    val mcuIrq = Output(Bool())
    val mcuSpiChipSelect = Input(Bool())
    val mcuSpiClock = Input(Bool())
    val mcuSpiDataIn = Input(UInt(4.W))
    val mcuSpiDataOut = Output(UInt(4.W))
    val mcuSpiDataDir = Output(UInt(4.W))

    val lcd = Output(new DpiSignals)
    val lcdData = Output(UInt(18.W))
    val dac = Output(new I2sSignals)

    /** Raw button input, not registered or inverted. */
    val buttons = Input(new HandheldButtons)

    // Cartridge I/O
    /** Cartridge switch: 1 when DMG/CGB cartridge inserted */
    val cartridgeSwitch = Input(Bool())
    val cartridge3V3Enable = Output(Bool())
    val cartridge5V0Enable = Output(Bool())
    /** Cartridge shifter output enable: active-low */
    val cartridgeOutputEnableN = Output(Bool())
    val cartridge = new HandheldCartridge

    val vibrate = Output(Bool())
    val pmod = new HandheldPmod
    val link = new HandheldLink

    // SRAM
    val sramA = Output(UInt(18.W))
    val sramIoIn = Input(UInt(16.W))
    val sramIoOut = Output(UInt(16.W))
    val sramIoDir = Output(Bool())
    val sramCeN = Output(Bool())
    val sramWeN = Output(Bool())
    val sramOeN = Output(Bool())
    val sramUbN = Output(Bool())
    val sramLbN = Output(Bool())

    // SDRAM
    val sdramClock = Output(Clock()) // TODO: phase shift, faster?
    val sdram = new SdramSignals(sdramConfig)
  })
  val moduleReset = WireDefault(false.B)
  val module = withReset(moduleReset) {
    Module(genT)
  }

  //////////////////////////////////
  // MCU Communication
  //////////////////////////////////
  // D0: PICO, D1: POCI
  val spi = Module(new SpiReceiverFifo())
  io.mcuIrq := false.B
  io.mcuSpiDataDir := Mux(io.mcuSpiChipSelect, 0.U, "b0010".U)
  io.mcuSpiDataOut := Cat(0.U(2.W), spi.io.signals.serialOut, 0.U(1.W))
  spi.io.signals.serialClock := io.mcuSpiClock
  spi.io.signals.serialIn := io.mcuSpiDataIn(0)
  spi.io.signals.chipSelect := io.mcuSpiChipSelect

  val tempRegister = RegInit(0.U(16.W))
  val countRegister = RegInit(0.U(16.W))
  val controlRegister = RegInit(0.U(3.W))
  val buttonRegister = RegInit(0.U.asTypeOf(new HandheldButtons))

  val registerMap = RegisterMap(
    addressWidth = 32,
    dataWidth = 16,
    entries = Seq(
      0x0 -> tempRegister,
      0x2 -> controlRegister,
      0x4 -> countRegister,
      0x6 -> buttonRegister,
    )
  )

  val sramSpiInterface = Wire(new MemoryInterface(addressWidth = 19, dataWidth = 16))
  val sdramSpiInterface = Wire(new MemoryInterface(addressWidth = 25, dataWidth = 32))
  val moduleMcuInterface = Wire(new MemoryInterface(addressWidth = 30, dataWidth = 32))

  spi.io.mem <> MemoryMap(
    addressWidth = 32,
    dataWidth = 32,
    entries = Seq(
      "b0000".U(4.W) -> registerMap,
      "b0001".U(4.W) -> sramSpiInterface,
      "b0010".U(4.W) -> sdramSpiInterface,
      "b11".U(2.W) -> moduleMcuInterface,
    ))

  moduleReset := !controlRegister(1)

  //////////////////////////////////
  // Memory
  //////////////////////////////////
  io.sramOeN := 1.U
  io.sramLbN := 0.U
  io.sramUbN := 0.U
  io.sramIoDir := !io.sramWeN

  io.sramCeN := 1.U
  io.sramA := DontCare
  io.sramIoOut := DontCare
  io.sramWeN := 1.U

  val sramArbiter = Module(new MemoryArbiter(addressWidth = 19, dataWidth = 16, n = 2))
  val sramInterface = sramArbiter.io.target
  sramArbiter.io.initiator(0) <> sramSpiInterface

  io.sramA := sramInterface.address(18, 1)
  sramInterface.done := false.B
  sramInterface.dataRead := DontCare
  when (sramInterface.read) {
    io.sramCeN := 0.U
    io.sramWeN := 1.U
    io.sramOeN := 0.U
    sramInterface.dataRead := io.sramIoIn
    sramInterface.done := true.B
  } .elsewhen (sramInterface.write) {
    io.sramCeN := 0.U
    io.sramWeN := 0.U
    io.sramLbN := !sramInterface.writeStrobe(0)
    io.sramUbN := !sramInterface.writeStrobe(1)
    io.sramIoOut := sramInterface.dataWrite
    sramInterface.done := true.B
  }

  // SDRAM
  val sdramArbiter = Module(new MemoryArbiter(addressWidth = 25, dataWidth = 32, n = 2))
  sdramArbiter.io.initiator(0) <> sdramSpiInterface
  val sdram = Module(new SdramController(sdramConfig))
  sdram.io.mem <> sdramArbiter.io.target
  io.sdramClock := clock // TODO
  io.sdram <> sdram.io.signals

  //////////////////////////////////
  // Video
  //////////////////////////////////
  val screenWidth = 480
  val screenHeight = 320
  val videoWidth = module.framebufferW
  val videoHeight = module.framebufferH
  val framebuffer = SyncReadMem(videoWidth * videoHeight, UInt(15.W))
  withClock (io.clock_av) {
    /**
     * DPI video signal output
     * dotclk = 12.288MHz, fps = 60
     * H = 320, total inactive = 88
     * V = 480, total inactive = 22
     */
    val dpiDriver = Module(new DpiDriver(
      hActive = screenHeight,
      hSync = 30, // min = 3
      hBackPorch = 29, // min = 3
      hFrontPorch = 29, // min = 3
      vActive = screenWidth,
      vSync = 8, // min = 1
      vBackPorch = 7, // min = 2
      vFrontPorch = 7, // min = 2
    ))
    io.lcd := dpiDriver.io.signals
    val dpiX = dpiDriver.io.pixelY
    val dpiY = dpiDriver.io.pixelX

    // 160x144 to 480x320 -- scale by 2, and center
    val videoScale = 2
    val videoOffsetX = (screenWidth - (videoWidth * videoScale)) / 2
    val videoOffsetY = (screenHeight - (videoHeight * videoScale)) / 2
    val framebufferReadDelay = 2 // 2 cycles to read from the framebuffer
    val framebufferReadAddress =
      (((dpiY - videoOffsetY.U(16.W) + framebufferReadDelay.U(16.W)) / videoScale.U(16.W)) * videoWidth.U(16.W)) +
        ((dpiX - videoOffsetX.U(16.W)) / videoScale.U)
    // Buffering the read allows this to be a block ram instead of distributed ram
    val framebufferRead = RegNext(framebuffer.read(framebufferReadAddress, io.clock_av))
    when (
      dpiX >= videoOffsetX.U(16.W) &&
        dpiX < (videoOffsetX + (videoWidth * videoScale)).U(16.W) &&
        dpiY >= videoOffsetY.U(16.W) &&
        dpiY < (videoOffsetY + (videoHeight * videoScale)).U(16.W)) {
      val framebufferReadR = framebufferRead(14, 10)
      val framebufferReadG = framebufferRead(9, 5)
      val framebufferReadB = framebufferRead(4, 0)
      io.lcdData := Cat(
        Cat(framebufferReadR, 0.U(1.W)),
        Cat(framebufferReadG, 0.U(1.W)),
        Cat(framebufferReadB, 0.U(1.W)),
      )
    } .otherwise {
      io.lcdData := 0.U(15.W)
    }
  }

  //////////////////////////////////
  // Audio
  //////////////////////////////////
  val audioDataHandshake = Module(new xpm_cdc_handshake(
    width = 32,
    destExtHsk = false,
  ))
  audioDataHandshake.io.src_clk := clock
  audioDataHandshake.io.dest_clk := io.clock_av
  audioDataHandshake.io.dest_ack := true.B // unused when destExtHsk = false
  withClock (io.clock_av) {
    val i2sTransmitter =
      Module(new I2sTransmitter(
        bitWidth = 16,
        mclkFactor = 256,
        channels = 2,
      ))
    io.dac := i2sTransmitter.io.signals

    val audioData = RegInit(0.U(32.W))
    when (audioDataHandshake.io.dest_req) {
      audioData := audioDataHandshake.io.dest_out
    }
    i2sTransmitter.io.dataLeft := audioData(31, 16)
    i2sTransmitter.io.dataRight := audioData(15, 0)
  }

  //////////////////////////////////
  // Submodule Connections
  //////////////////////////////////
  module.io.enable := controlRegister(0)
  io.vibrate := (module.io.enable && module.io.vibrate) || controlRegister(2)
  io.link <> module.io.link
  io.pmod <> module.io.pmod
  module.io.mcuInterface <> moduleMcuInterface

  // Buttons must be synchronized and inverted.
  module.io.buttons :=
    (RegNext(RegNext(~io.buttons.asUInt)).asUInt | buttonRegister.asUInt).asTypeOf(new HandheldButtons)

  // Framebuffer writes
  when (module.io.framebufferWriteEnable) {
    val address = (module.io.framebufferY * videoWidth.U(8.W)) + module.io.framebufferX
    val data = Cat(module.io.framebufferDataR, module.io.framebufferDataG, module.io.framebufferDataB)
    framebuffer.write(address, data)
  }

  // Audio sample synchronization
  val audioDataSend = RegInit(false.B)
  audioDataHandshake.io.src_in := Cat(module.io.audioLeft.asUInt, module.io.audioRight.asUInt)
  audioDataHandshake.io.src_send := audioDataSend
  when (!audioDataHandshake.io.src_rcv && !audioDataSend) {
    audioDataSend := true.B
  }
  when (audioDataHandshake.io.src_rcv && audioDataSend) {
    audioDataSend := false.B
  }

  // Cartridge
  io.cartridge <> module.io.cartridge
  io.cartridgeOutputEnableN := !module.io.cartridgeEnabled
  io.cartridge3V3Enable := !io.cartridgeSwitch && module.io.cartridgeEnabled
  io.cartridge5V0Enable := io.cartridgeSwitch && module.io.cartridgeEnabled

  // Memories
  sramArbiter.io.initiator(1) <> module.io.sram
  sdramArbiter.io.initiator(1) <> module.io.sdram
}
