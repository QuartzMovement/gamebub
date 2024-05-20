package gba.ppu

import chisel3._
import chisel3.util._

object Compositor {
  class Layer extends Bundle {
    val valid = Bool()
    val bgIndex = UInt(2.W)
    val isBg = Bool()
    val color = UInt(15.W)
  }
}

class Compositor extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val displayControl = Input(new PpuRegisters.DisplayControl)
    val tick = Input(UInt(11.W))
    val scanline = Input(UInt(8.W))

    val paletteRam = Flipped(new PpuMemoryInterface(1024 / 2, 16.W))

    val valid = Output(Bool())
    val pixel = Output(UInt(15.W))

    val bgFifo = Flipped(DecoupledIO(new BackgroundPixel))
  })
  val isBitmap16bpp = io.displayControl.mode === 3.U || io.displayControl.mode === 5.U

  io.bgFifo.ready := false.B
  io.paletteRam.read := false.B
  io.paletteRam.address := DontCare

  val outX = Reg(UInt(8.W))
  val active = Reg(Bool())
  when (io.enable) {
    when (io.tick === 0.U) {
      outX := 0.U
    }
    when (io.tick === 45.U && io.scanline < 160.U) {
      active := true.B
    }
    when (outX === 240.U) {
      active := false.B
    }
  }
  val subCycle = (io.tick - 2.U)(1, 0)

  val regLayerTop = Reg(new Compositor.Layer)

  io.valid := false.B
  io.pixel := DontCare
  when (io.enable && active) {
    switch (subCycle) {
      // Sort layers, fetch top layer palette entry
      is (0.U) {
        // TODO bring objects
        // TODO background priority

        // Pull the next pixel.
        io.bgFifo.ready := true.B
        val bgData = io.bgFifo.bits

        val topLayer = Wire(new Compositor.Layer)
        topLayer.valid := bgData.valid(2)
        topLayer.color := bgData.color(2)
        topLayer.bgIndex := 2.U
        topLayer.isBg := true.B
        regLayerTop := topLayer

        // Start palette RAM read 1
        when (topLayer.valid) {
          when (topLayer.isBg && isBitmap16bpp) {
            regLayerTop.color := Cat(bgData.color(3), bgData.color(2))
          } .otherwise {
            io.paletteRam.read := true.B
            io.paletteRam.address := topLayer.color
          }
        } .otherwise {
          // No valid layer, use the backdrop (palette index 0)
          io.paletteRam.read := true.B
          io.paletteRam.address := 0.U
        }
      }
      // Store top layer palette entry
      is (1.U) {
        when (!(regLayerTop.isBg && isBitmap16bpp && regLayerTop.valid)) {
          regLayerTop.color := io.paletteRam.readData
        }
      }
      // Start fetch of bottom layer palette entry
      is (2.U) {

      }
      is (3.U) {
        // Do final composite.
        io.valid := true.B
        io.pixel := regLayerTop.color
        outX := outX + 1.U
      }
    }
  }
}
