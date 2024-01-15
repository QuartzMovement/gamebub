package platform.handheld

import chisel3._
import chisel3.util._
import xilinx.xpm_cdc_handshake

object HandheldTop extends App {
  emitVerilog(new HandheldTop, args)
}

/** IO bundle used for a handheld submodule. */
class HandheldIo extends Bundle {
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

  // TODO SRAM
  // TODO SDRAM
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
 * Expects an outer clock of 12.288 MHz.
 */
class HandheldTop extends Module {
  val io = IO(new Bundle {
    val clk_8mhz = Input(Clock())

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
  })
  /**
   * DPI video signal output
   * dotclk = 12.288MHz, fps = 60
   * H = 320, total inactive = 88
   * V = 480, total inactive = 22
   */
  val dpiDriver = Module(new DpiDriver(
    hActive = 320,
    hSync = 30, // min = 3
    hBackPorch = 29, // min = 3
    hFrontPorch = 29, // min = 3
    vActive = 480,
    vSync = 8, // min = 1
    vBackPorch = 7, // min = 2
    vFrontPorch = 7, // min = 2
  ))
  io.lcd := dpiDriver.io.signals
  val dpiX = dpiDriver.io.pixelY
  val dpiY = dpiDriver.io.pixelX

  // 160x144 to 480x320 -- scale by 2, and center
  val framebuffer = SyncReadMem(160 * 144, UInt(15.W))
  val videoWidth = 160
  val videoHeight = 144
  val videoScale = 2
  val videoOffsetX = (480 - (videoWidth * videoScale)) / 2
  val videoOffsetY = (320 - (videoHeight * videoScale)) / 2
  val framebufferReadDelay = 2 // 2 cycles to read from the framebuffer
  val framebufferReadAddress =
    (((dpiY - videoOffsetY.U(16.W) + framebufferReadDelay.U(16.W)) / videoScale.U(16.W)) * videoWidth.U(16.W)) +
      ((dpiX - videoOffsetX.U(16.W)) / videoScale.U)
  // Buffering the read allows this to be a block ram instead of distributed ram
  val framebufferRead = RegNext(framebuffer.read(framebufferReadAddress, true.B))
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

  // Audio transmission
  val i2sTransmitter = Module(new I2sTransmitter(
    bitWidth = 16,
    mclkFactor = 256,
    channels = 2,
  ))
  io.dac := i2sTransmitter.io.signals

  val audioData = RegInit(0.U(32.W))
  val audioDataHandshake = Module(new xpm_cdc_handshake(
    width = 32,
    destExtHsk = false,
  ))
  audioDataHandshake.io.src_clk := io.clk_8mhz
  audioDataHandshake.io.dest_clk := clock
  audioDataHandshake.io.dest_ack := true.B // unused when destExtHsk = false
  when (audioDataHandshake.io.dest_req) {
    audioData := audioDataHandshake.io.dest_out
  }
  i2sTransmitter.io.dataLeft := audioData(31, 16)
  i2sTransmitter.io.dataRight := audioData(15, 0)

  // Submodule
  withClock (io.clk_8mhz) {
    val tempInnerReset = !RegNext(RegNext(io.buttons.r))
    val module = withReset(tempInnerReset) {
      Module(new HandheldGameboy)
    }

    io.vibrate := module.io.vibrate || !io.buttons.l // XXX: remove L-activation. For testing only
    io.link <> module.io.link
    io.pmod <> module.io.pmod

    // Buttons must be synchronized and inverted.
    module.io.buttons := RegNext(RegNext(~io.buttons.asUInt)).asTypeOf(new HandheldButtons)

    // Framebuffer writes
    when (module.io.framebufferWriteEnable) {
      val address = (module.io.framebufferY * 160.U(8.W)) + module.io.framebufferX
      val data = Cat(module.io.framebufferDataR, module.io.framebufferDataG, module.io.framebufferDataB)
      framebuffer.write(address, data, io.clk_8mhz)
    }

    // Audio sample synchronization
    val audioDataSend = RegInit(false.B)
    audioDataHandshake.io.src_in := Cat(module.io.audioLeft.asUInt, module.io.audioRight.asUInt)
    audioDataHandshake.io.src_send := audioDataSend
    when(!audioDataHandshake.io.src_rcv && !audioDataSend) {
      audioDataSend := true.B
    }
    when(audioDataHandshake.io.src_rcv && audioDataSend) {
      audioDataSend := false.B
    }

    // Cartridge
    io.cartridge <> module.io.cartridge
    io.cartridgeOutputEnableN := !module.io.cartridgeEnabled
    io.cartridge3V3Enable := !io.cartridgeSwitch && module.io.cartridgeEnabled
    io.cartridge5V0Enable := io.cartridgeSwitch && module.io.cartridgeEnabled
  }
}
