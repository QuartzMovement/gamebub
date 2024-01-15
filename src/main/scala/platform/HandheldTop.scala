package platform

import chisel3._
import chisel3.util._
import gameboy.apu.ApuOutput
import gameboy.{CartridgeIo, SerialIo}
import platform.handheld.{HandheldGameboy, I2sSignals, I2sTransmitter}
import xilinx.xpm_cdc_handshake

object HandheldTop extends App {
  emitVerilog(new HandheldTop, args)
}

class Buttons extends Bundle {
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

class HandheldTop extends Module {
  val io = IO(new Bundle {
    val clk_8mhz = Input(Clock())

    val lcd = Output(new DpiSignals)
    val lcdData = Output(UInt(18.W))

    val dac = Output(new I2sSignals)

    val buttons = Input(new Buttons)

    val cartridge = new CartridgeIo()
    val serial = new SerialIo()
    val tCycle = Output(UInt(2.W))

    val pmod = Output(UInt(4.W))
  })
  io.pmod := 0.U

  /**
   * DPI:
   * dotclk = 12.288MHz, fps = 60
   * H = 320, total inactive = 88
   * V = 480, total inactive = 22
   *
   * (for 10MHz, do H_inact=14, V_inact=19)
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

  val framebuffer = SyncReadMem(160 * 144, UInt(15.W))

  // 160x144 to 480x320 -- scale by 2, and center
  val width = 160
  val height = 144
  val scale = 2
  val offsetX = (480 - (width * scale)) / 2
  val offsetY = (320 - (height * scale)) / 2
  val framebufferReadDelay = 1
  val framebufferAddress =
    (((dpiY - offsetY.U(16.W) + framebufferReadDelay.U(16.W)) / scale.U(16.W)) * width.U(16.W)) +
      ((dpiX - offsetX.U(16.W)) / scale.U)

  when (
    dpiX >= offsetX.U(16.W) &&
      dpiX < (offsetX + (width * scale)).U(16.W) &&
      dpiY >= offsetY.U(16.W) &&
      dpiY < (offsetY + (height * scale)).U(16.W)) {
    val framebufferRead = framebuffer.read(framebufferAddress, true.B)
    val framebufferReadR = framebufferRead(4, 0)
    val framebufferReadG = framebufferRead(9, 5)
    val framebufferReadB = framebufferRead(14, 10)
    io.lcdData := Cat(
      Cat(framebufferReadR, 0.U(1.W)),
      Cat(framebufferReadG, 0.U(1.W)),
      Cat(framebufferReadB, 0.U(1.W)),
    )
  } .otherwise {
    io.lcdData := 0.U(15.W)
  }

  // Audio transission
  val i2sTransmitter = Module(new I2sTransmitter(
    bitWidth = 16,
    mclkFactor = 256,
    channels = 2,
  ))
  io.dac := i2sTransmitter.io.signals

  val audioData = RegInit(0.U(20.W))
  val audioDataHandshake = Module(new xpm_cdc_handshake(
    width = 20,
    destExtHsk = false,
  ))
  audioDataHandshake.io.src_clk := io.clk_8mhz
  audioDataHandshake.io.dest_clk := clock
  audioDataHandshake.io.dest_ack := true.B // unused when destExtHsk = false
  when (audioDataHandshake.io.dest_req) {
    audioData := audioDataHandshake.io.dest_out
  }
  i2sTransmitter.io.dataLeft := audioData(19, 10) << 6
  i2sTransmitter.io.dataRight := audioData(9, 0) << 6

  // Gameboy
  withClock (io.clk_8mhz) {
    val tempGbReset = !RegNext(RegNext(io.buttons.x))
    val gameboy = withReset(tempGbReset) {
      Module(new HandheldGameboy)
    }
    gameboy.io.joypad.a := !RegNext(RegNext(io.buttons.a))
    gameboy.io.joypad.b := !RegNext(RegNext(io.buttons.b))
    gameboy.io.joypad.start := !RegNext(RegNext(io.buttons.start))
    gameboy.io.joypad.select := !RegNext(RegNext(io.buttons.select))
    gameboy.io.joypad.up := !RegNext(RegNext(io.buttons.up))
    gameboy.io.joypad.down := !RegNext(RegNext(io.buttons.down))
    gameboy.io.joypad.left := !RegNext(RegNext(io.buttons.left))
    gameboy.io.joypad.right := !RegNext(RegNext(io.buttons.right))
    io.cartridge <> gameboy.io.cartridge
    io.tCycle := gameboy.io.tCycle
    io.serial <> gameboy.io.serial

    val audioDataSend = RegInit(false.B)
    audioDataHandshake.io.src_in := Cat(gameboy.io.apu.left.asUInt, gameboy.io.apu.right.asUInt)
    audioDataHandshake.io.src_send := audioDataSend
    when (!audioDataHandshake.io.src_rcv && !audioDataSend) {
      audioDataSend := true.B
    }
    when (audioDataHandshake.io.src_rcv && audioDataSend) {
      audioDataSend := false.B
    }

    when(gameboy.io.framebufferWriteEnable) {
      framebuffer.write(gameboy.io.framebufferWriteAddr, gameboy.io.framebufferWriteData, io.clk_8mhz)
    }
  }
}
