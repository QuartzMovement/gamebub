package platform

import chisel3._
import chisel3.util._
import platform.handheld.{I2sSignals, I2sTransmitter}

object HandheldTop extends App {
  emitVerilog(new HandheldTop, args)
}

class HandheldTop extends Module {
  val io = IO(new Bundle {
    val lcd = Output(new DpiSignals)
    val lcdData = Output(UInt(18.W))

    val i2s = Output(new I2sSignals)
  })

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

  val timer = RegInit(0.U(6.W))
  when (io.lcd.vsync && !RegNext(io.lcd.vsync)) {
    timer := timer + 1.U
  }

  // XOR test pattern -- moves to left, columns are R, G, B
  // X and Y are flipped:
  val x = dpiDriver.io.pixelY
  val y = dpiDriver.io.pixelX
  val color = (timer + x(5, 0)) ^ y
  when (x < 160.U) {
    io.lcdData := Cat(color, 0.U(6.W), 0.U(6.W))
  } .elsewhen (x < 320.U) {
    io.lcdData := Cat(0.U(6.W), color, 0.U(6.W))
  } .otherwise {
    io.lcdData := Cat(0.U(6.W), 0.U(6.W), color)
  }

  // Audio test. sample rate = 48KHz. 480Hz tone means period of 100 samples (50 up, 50 down)
  val i2sTransmitter = Module(new I2sTransmitter(
    bitWidth = 16,
    mclkFactor = 256,
    channels = 2,
  ))
  io.i2s := i2sTransmitter.io.signals

  // I2S is 2s complement. This should switch between -32k and 32k
  val audioData = RegInit(0x8000.U(16.W))
  val sampleCounter = Counter(50)
  when (i2sTransmitter.io.sampleEnable) {
    when (sampleCounter.inc()) {
      audioData := ~audioData
    }
  }
  i2sTransmitter.io.dataLeft := audioData
  i2sTransmitter.io.dataRight := audioData
}
