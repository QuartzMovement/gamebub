package platform.handheld

import chisel3._
import chisel3.util._
import lib.mem.{MemoryArbiter, MemoryCdc, MemoryInterface, MemoryMap, RegisterMap}
import lib.video.ColorARGB
import xilinx.XpmCdcHandshake

object HandheldTop extends App {

  emitVerilog(new HandheldTop(
    new HandheldGameboy
//    new HandheldTester
  ), args)
}

/** IO bundle used for a handheld submodule. */
class HandheldIo extends Bundle {
  val enable = Input(Bool())

  val buttons = Input(new HandheldButtons)

  // Video output
  val framebufferX = Output(UInt(8.W))
  val framebufferY = Output(UInt(8.W))
  val framebufferData = Output(ColorARGB.rgb555())
  val framebufferWriteEnable = Output(Bool())
  val vblank = Output(Bool())

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

class HandheldInterrupts extends Bundle {
  val moduleVblank = Bool()
}

/**
 * Top-level Chisel module for the handheld.
 *
 * The outer clock is passed down to the inner module,
 * e.g. 8.3886 MHz for Gameboy.
 */
class HandheldTop[T <: Module with HandheldModule](genT: => T) extends Module {
  val sdramConfig = SdramController.Config(
    clockFrequency = 32 * 1024 * 1024,
    burstLength = 2,
    timeRsc = 60, /* 2 clocks */
    timeWr = 60, /* 2 clocks */
  )
  val io = IO(new Bundle {
    /** Audio/video clock: 12.288 MHz */
    val clock_av = Input(Clock())

    /** SPI clocking */
    val clockSpi = Input(Clock())
    val clockSpiLocked = Input(Bool())
    val clockSpiPowerDown = Output(Bool())

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
    val sdramClock = Input(Clock())
    val sdram = new SdramController.Signals(sdramConfig)
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
  spi.io.clockSpi := io.clockSpi
  spi.io.clockSpiLocked := io.clockSpiLocked
  io.clockSpiPowerDown := spi.io.clockSpiPowerDown
  io.mcuSpiDataDir := Mux(io.mcuSpiChipSelect, 0.U, "b0010".U)
  io.mcuSpiDataOut := Cat(0.U(2.W), spi.io.signals.serialOut, 0.U(1.W))
  spi.io.signals.serialClock := io.mcuSpiClock
  spi.io.signals.serialIn := io.mcuSpiDataIn(0)
  spi.io.signals.chipSelect := io.mcuSpiChipSelect

  val controlRegister = RegInit(0.U.asTypeOf(new Bundle() {
    /** 1 to activate the vibration motor (TODO change to enable, not activate) */
    val vibrate = Bool()
    /** Whether the module is currently in vblank. (TODO make read-only) */
    val moduleVblank = Bool()
    /** Active-low reset for the inner module. */
    val moduleReset = Bool()
    /** Active-high enable for the inner module. */
    val moduleEnable = Bool()
  }))
  val buttonRegister = RegInit(0.U.asTypeOf(new HandheldButtons))
  val spiStatusRegister = RegInit(0.U.asTypeOf(new Bundle() {
    val requestFifoOverflow = Bool()
    val responseFifoUnderflow = Bool()
  }))
  val interruptEnable = RegInit(0.U.asTypeOf(new HandheldInterrupts))
  val interruptFlags = RegInit(0.U.asTypeOf(new HandheldInterrupts))

  val overlayXControlRegister = RegInit(0.U.asTypeOf(new Bundle() {
    val start = UInt(8.W)
    val end = UInt(8.W)
    val scroll = UInt(8.W)
  }))
  val overlayYControlRegister = RegInit(0.U.asTypeOf(new Bundle() {
    val start = UInt(8.W)
    val end = UInt(8.W)
    val scroll = UInt(8.W)
  }))

  val registerMap = RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries = Seq(
      0x0 -> RegisterMap.Entry.rw(controlRegister),
      0x4 -> RegisterMap.Entry.rw(buttonRegister),
      0x8 -> RegisterMap.Entry.rw(spiStatusRegister),
      0xC -> RegisterMap.Entry.rw(interruptEnable),
      0x10 -> RegisterMap.Entry.apply(
        interruptFlags.getWidth,
        read = RegisterMap.ReadFn((_: Bool) => interruptFlags.asUInt),
        write = RegisterMap.WriteFn((write: Bool, data: UInt) =>
          when (write) {
            // Write set bits to ack interrupts.
            interruptFlags := (interruptFlags.asUInt & ~data.asUInt).asTypeOf(interruptFlags)
          }
        ),
      ),
      // Overlay control
      0x100 -> RegisterMap.Entry.rw(overlayXControlRegister),
      0x104 -> RegisterMap.Entry.rw(overlayYControlRegister),
      // Framebuffer dimensions
      0x200 -> RegisterMap.Entry.r(
        Cat(module.framebufferW.U(16.W), module.framebufferH.U(16.W))),
    )
  )

  val sramSpiInterface = Wire(new MemoryInterface(addressWidth = 19, dataWidth = 16))
  val sdramSpiInterface = Wire(new MemoryInterface(addressWidth = 25, dataWidth = 32))
  val moduleMcuInterface = Wire(new MemoryInterface(addressWidth = 30, dataWidth = 32))
  val overlayInterface = Wire(new MemoryInterface(addressWidth = 18, dataWidth = 16))
  val framebufferInterface = Wire(new MemoryInterface(addressWidth = 18, dataWidth = 16))
  spi.io.mem <> MemoryMap(
    addressWidth = 32,
    dataWidth = 32,
    entries = Seq(
      "b0000".U(4.W) -> registerMap,
      "b0001".U(4.W) -> sramSpiInterface,
      "b0010".U(4.W) -> sdramSpiInterface,
      "b001110".U(6.W) -> overlayInterface,
      "b001111".U(6.W) -> framebufferInterface,
      "b11".U(2.W) -> moduleMcuInterface,
    ))

  moduleReset := !controlRegister.moduleReset
  controlRegister.moduleVblank := module.io.vblank
  when (spi.io.debugRequestOverflow) {
    spiStatusRegister.requestFifoOverflow := true.B
  }
  when (spi.io.debugResponseUnderflow) {
    spiStatusRegister.responseFifoUnderflow := true.B
  }

  //////////////////////////////////
  // Interrupts
  //////////////////////////////////
  io.mcuIrq := (interruptFlags.asUInt & interruptEnable.asUInt).orR
  when (module.io.vblank) {
    interruptFlags.moduleVblank := true.B
  }


  io.pmod.dir := "b1111".U
  io.pmod.out := Cat(clock.asBool, spi.io.mem.read || spi.io.mem.write, spi.io.mem.done, spiStatusRegister.requestFifoOverflow || spiStatusRegister.responseFifoUnderflow)

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

  val sdram = withClock(io.sdramClock) {
    Module(new SdramController(sdramConfig))
  }
  io.sdram <> sdram.io.signals

  withClock(io.sdramClock) {
    val cdc = Module(new MemoryCdc(addressWidth = 25, dataWidth = 32))
    cdc.io.slowClock := clock
    cdc.io.initiator <> sdramArbiter.io.target
    cdc.io.target <> sdram.io.mem
  }

  //////////////////////////////////
  // Video
  //////////////////////////////////
  val screenWidth = 480
  val screenHeight = 320
  val videoWidth = module.framebufferW
  val videoHeight = module.framebufferH
  val framebuffer = SyncReadMem(videoWidth * videoHeight, UInt(ColorARGB.rgb555().getWidth.W))

  val overlayScale = 2
  val overlayWidth = screenWidth / overlayScale
  val overlayHeight = screenHeight / overlayScale
  val overlayFramebuffer = SyncReadMem(overlayWidth * overlayHeight, UInt(ColorARGB.argb1555().getWidth.W))
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

    // Scale and center framebuffer within output video.
    val videoScale = 2
    val videoOffsetX = (screenWidth - (videoWidth * videoScale)) / 2
    val videoOffsetY = (screenHeight - (videoHeight * videoScale)) / 2
    val framebufferReadDelay = 3 // 3 cycles to read from the framebuffer
    val framebufferReadAddress =
      (((dpiY - videoOffsetY.U(16.W) + framebufferReadDelay.U(16.W)) / videoScale.U(16.W)) * videoWidth.U(16.W)) +
        ((dpiX - videoOffsetX.U(16.W)) / videoScale.U)
    // Buffering the read allows this to be a block ram instead of distributed ram
    // and an additional output buffer allows Vivado to improve timing.
    val framebufferRead = RegNext(RegNext(framebuffer.read(framebufferReadAddress, io.clock_av))).asTypeOf(ColorARGB.rgb555())

    // Similar for overlay framebuffer.
    val overlayXControl = XpmCdcHandshake.continuous(clock, overlayXControlRegister)
    val overlayYControl = XpmCdcHandshake.continuous(clock, overlayYControlRegister)
    val overlayReadDelay = 3
    val overlayReadAddress =
      ((((dpiY + overlayReadDelay.U(16.W)) / overlayScale.U(16.W)) + overlayYControl.scroll)(7, 0) * overlayWidth.U(16.W)) +
        ((dpiX / overlayScale.U) + overlayXControl.scroll)(7, 0)
    val overlayRead = RegNext(RegNext(overlayFramebuffer.read(overlayReadAddress, io.clock_av))).asTypeOf(ColorARGB.argb1555())

    val videoOutput = ColorARGB.rgb555().makeBlack()
    when (
      dpiX >= videoOffsetX.U(16.W) &&
        dpiX < (videoOffsetX + (videoWidth * videoScale)).U(16.W) &&
        dpiY >= videoOffsetY.U(16.W) &&
        dpiY < (videoOffsetY + (videoHeight * videoScale)).U(16.W)) {
      videoOutput := framebufferRead
    }
    when (
      !overlayRead.a &&
        dpiX >= (overlayXControl.start * overlayScale.U) &&
        dpiX < (overlayXControl.end * overlayScale.U) &&
        dpiY >= (overlayYControl.start * overlayScale.U) &&
        dpiY < (overlayYControl.end * overlayScale.U)
    ) {
      videoOutput := overlayRead
    }
    // Pad to 18-bit RGB.
    io.lcdData := Cat(
      Cat(videoOutput.r, 0.U(1.W)),
      Cat(videoOutput.g, 0.U(1.W)),
      Cat(videoOutput.b, 0.U(1.W)),
    )
  }

  // Overlay access.
  // TODO: consider switching to (or adding) a method of writing where
  // there's a "target x" and "target y" register, and you write to a single
  // memory location, which auto-increments the x. Then, have registers for
  // minX (where it wraps to) and maxX (when it wraps), which allows for easy
  // partial rectangular updates.
  overlayInterface.dataRead := DontCare
  overlayInterface.done := false.B
  when (overlayInterface.read) {
    // Reads are not supported.
    overlayInterface.done := true.B
  }
  when (overlayInterface.write) {
    overlayFramebuffer.write(
      (overlayInterface.address >> 1.U).asUInt,
      overlayInterface.dataWrite
    )
    overlayInterface.done := true.B
  }

  // Framebuffer read via memory.
  framebufferInterface.dataRead := RegNext(RegNext(
    framebuffer.read((framebufferInterface.address >> 1.U).asUInt, framebufferInterface.read)
  ))
  framebufferInterface.done := RegNext(RegNext(framebufferInterface.read))
  when (framebufferInterface.write) {
    // Writes are not supported.
    framebufferInterface.done := true.B
  }

  //////////////////////////////////
  // Audio
  //////////////////////////////////
  withClock (io.clock_av) {
    val i2sTransmitter =
      Module(new I2sTransmitter(
        bitWidth = 16,
        mclkFactor = 256,
        channels = 2,
      ))
    io.dac := i2sTransmitter.io.signals

    val audioData = XpmCdcHandshake.continuous(clock,
      Cat(module.io.audioLeft.asUInt, module.io.audioRight.asUInt))
    i2sTransmitter.io.dataLeft := audioData(31, 16)
    i2sTransmitter.io.dataRight := audioData(15, 0)
  }

  //////////////////////////////////
  // Submodule Connections
  //////////////////////////////////
  module.io.enable := controlRegister.moduleEnable
  io.vibrate := (module.io.enable && module.io.vibrate) || controlRegister.vibrate
  io.link <> module.io.link
//  io.pmod <> module.io.pmod
  module.io.pmod.in := 0.U
  module.io.mcuInterface <> moduleMcuInterface

  // Buttons must be synchronized and inverted.
  module.io.buttons :=
    (RegNext(RegNext(~io.buttons.asUInt)).asUInt | buttonRegister.asUInt).asTypeOf(new HandheldButtons)

  // Framebuffer writes
  when (module.io.framebufferWriteEnable) {
    // Module framebuffer write and SPI framebuffer read share the same read/write port,
    // so ensure that they're not activated at the same time (so they can be inferred correctly).
    when (!framebufferInterface.read) {
      val address = (module.io.framebufferY * videoWidth.U(8.W)) + module.io.framebufferX
      framebuffer.write(address, module.io.framebufferData.asUInt)
    }
  }

  // N.B. Audio synchronization happens above.

  // Cartridge
  io.cartridge <> module.io.cartridge
  io.cartridgeOutputEnableN := !module.io.cartridgeEnabled
  io.cartridge3V3Enable := !io.cartridgeSwitch && module.io.cartridgeEnabled
  io.cartridge5V0Enable := io.cartridgeSwitch && module.io.cartridgeEnabled

  // Memories
  sramArbiter.io.initiator(1) <> module.io.sram
  sdramArbiter.io.initiator(1) <> module.io.sdram
}
