package gba.ppu

import chisel3._
import chisel3.util._

object Compositor {
  class Layer extends Bundle {
    val opaque = Bool()
    val bgIndex = UInt(2.W)
    val isBg = Bool()
    val color = UInt(15.W)
    val priority = UInt(2.W)
  }
}

class Compositor extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val displayControl = Input(new PpuRegisters.DisplayControl)
    val bgControl = Input(Vec(4, new PpuRegisters.BackgroundControl))
    val tick = Input(UInt(11.W))
    val scanline = Input(UInt(8.W))

    val paletteRam = Flipped(new PpuMemoryInterface(1024 / 2, 16.W))

    val valid = Output(Bool())
    val pixel = Output(UInt(15.W))

    val bgFifo = Vec(4, Flipped(DecoupledIO(new BackgroundPixel)))

    val objectIndex = Output(UInt(8.W))
    val objectRead = Output(Bool())
    val objectData = Input(new ObjectBufferEntry)
  })

  val isBitmap16bpp = io.displayControl.mode === 3.U || io.displayControl.mode === 5.U

  io.paletteRam.read := false.B
  io.paletteRam.address := DontCare
  io.objectRead := false.B
  for (i <- 0 until 4) {
    io.bgFifo(i).ready := false.B
  }

  val outX = Reg(UInt(8.W))
  val active = Reg(Bool())
  when (io.enable) {
    when (io.tick === 0.U) {
      outX := 0.U
    }
    when (io.tick === 41.U && io.scanline < 160.U) {
      active := true.B
      io.objectRead := true.B
    }
    when (outX === 240.U) {
      active := false.B
    }
  }
  val subCycle = (io.tick - 2.U)(1, 0)

  val regLayerFirst = Reg(new Compositor.Layer)
  val regLayerSecond = Reg(new Compositor.Layer)
  io.valid := false.B
  io.pixel := DontCare
  io.objectIndex := outX
  when (io.enable && active && io.tick >= 46.U) {
    switch (subCycle) {
      // Start top layer palette entry fetch
      is (0.U) {
        when (regLayerFirst.opaque) {
          when (regLayerFirst.isBg && isBitmap16bpp) {
            // Special case: 16bpp bitmap, take bits from the BG3 fifo as well.
            io.bgFifo(3).ready := true.B
            regLayerFirst.color := Cat(io.bgFifo(3).bits.color, regLayerFirst.color(7, 0))
          } .otherwise {
            io.paletteRam.read := true.B
            io.paletteRam.address := Cat(!regLayerFirst.isBg, regLayerFirst.color(7, 0))
          }
        } .otherwise {
          // No valid layer, use the backdrop (palette index 0)
          io.paletteRam.read := true.B
          io.paletteRam.address := 0.U
        }
      }
      // Store top layer palette entry
      is (1.U) {
        when (!(regLayerFirst.isBg && isBitmap16bpp && regLayerFirst.opaque)) {
          regLayerFirst.color := io.paletteRam.readData
        }
      }
      // Start fetch of bottom layer palette entry
      is (2.U) {
        // TODO
      }
      // Do final composite.
      is (3.U) {
        io.valid := true.B
        io.pixel := regLayerFirst.color
        outX := outX + 1.U
        io.objectRead := true.B
      }
    }
  }

  // First stage: priority sorting: should start on cycle 42 (subCycle = 3)
  val regSortFirst = Reg(new Compositor.Layer)
  val regSortSecond = Reg(new Compositor.Layer)
  when (io.enable && active) {
    val nextFirstLayer = WireDefault(regSortFirst)
    val nextSecondLayer = WireDefault(regSortSecond)

    val bgFifo = io.bgFifo(subCycle)
    val bgPriority = io.bgControl(subCycle).priority
    when (io.displayControl.enableBg(subCycle)) {
      bgFifo.ready := true.B
    }
    when (bgFifo.valid && bgFifo.bits.opaque && io.displayControl.enableBg(subCycle)) {
      when (!regSortFirst.opaque || bgPriority < regSortFirst.priority) {
        nextFirstLayer.opaque := true.B
        nextFirstLayer.color := bgFifo.bits.color
        nextFirstLayer.priority := bgPriority
        nextFirstLayer.isBg := true.B
        nextSecondLayer := regSortFirst
      } .elsewhen (!regSortSecond.opaque || bgPriority < regSortSecond.priority) {
        nextSecondLayer.opaque := true.B
        nextSecondLayer.color := bgFifo.bits.color
        nextSecondLayer.priority := bgPriority
        nextSecondLayer.isBg := true.B
      }
    }

    // Last cycle: set up palette fetch and set up next priority sorting.
    when (subCycle === 3.U) {
      // Palette fetch
      regLayerFirst := nextFirstLayer
      regLayerSecond := nextSecondLayer

      // Set up the next set by fetching an object.
      regSortFirst.opaque := io.objectData.opaque
      regSortFirst.color := io.objectData.color
      regSortFirst.priority := io.objectData.priority
      regSortFirst.isBg := false.B
      regSortSecond.opaque := false.B
    } .otherwise {
      regSortFirst := nextFirstLayer
      regSortSecond := nextSecondLayer
    }
  }
}
