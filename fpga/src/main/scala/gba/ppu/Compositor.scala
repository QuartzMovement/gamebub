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

/// PPU compositor
///
/// 1) Pull data from object render buffer and background render FIFOs
/// 2) Apply windowing to layers
/// 3) Sort by priority
/// 4) Fetch palette entries for top two layers
/// 5) Apply blend effects (TODO)
/// 6) Output final pixel
class Compositor extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    val displayControl = Input(new PpuRegisters.DisplayControl)
    val bgControl = Input(Vec(4, new PpuRegisters.BackgroundControl))
    val win0Bounds = Input(new PpuRegisters.WindowBounds)
    val win1Bounds = Input(new PpuRegisters.WindowBounds)
    val win0Control = Input(new PpuRegisters.WindowControl)
    val win1Control = Input(new PpuRegisters.WindowControl)
    val winOutControl = Input(new PpuRegisters.WindowControl)
    val winObjControl = Input(new PpuRegisters.WindowControl)

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
  io.objectIndex := DontCare
  for (i <- 0 until 4) {
    io.bgFifo(i).ready := false.B
  }

  val win0ActiveX = Reg(Bool())
  val win1ActiveX = Reg(Bool())
  val win0ActiveY = Reg(Bool())
  val win1ActiveY = Reg(Bool())

  val fetchX = Reg(UInt(8.W))
  val active = Reg(Bool())
  when (io.enable) {
    when (io.tick === 0.U) {
      fetchX := 0.U

      // Window Y activation / deactivation
      when (io.scanline === io.win0Bounds.yStart) {
        win0ActiveY := true.B
      }
      when (io.scanline === io.win0Bounds.yEnd) {
        win0ActiveY := false.B
      }
      when (io.scanline === io.win1Bounds.yStart) {
        win1ActiveY := true.B
      }
      when (io.scanline === io.win1Bounds.yEnd) {
        win1ActiveY := false.B
      }
    }
    when (io.tick === 40.U && io.scanline < 160.U) {
      active := true.B
    }
    when (io.tick === 1006.U) {
      active := false.B
    }

    // Window X activation / deactivation. Happens even during vblank.
    when (io.tick >= 40.U && io.tick(1, 0) === 0.U) {
      val x = (io.tick - 40.U) >> 2
      when (x === io.win0Bounds.xStart) {
        win0ActiveX := true.B
      }
      when (x === io.win0Bounds.xEnd) {
        win0ActiveX := false.B
      }
      when (x === io.win1Bounds.xStart) {
        win1ActiveX := true.B
      }
      when (x === io.win1Bounds.xEnd) {
        win1ActiveX := false.B
      }
    }
  }
  val subCycle = (io.tick - 2.U)(1, 0)

  val regLayerFirst = Reg(new Compositor.Layer)
  val regLayerSecond = Reg(new Compositor.Layer)
  io.valid := false.B
  io.pixel := DontCare
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
      }
    }
  }

  // First stage: priority sorting: should start on cycle 42 (subCycle = 3)
  val regSortFirst = Reg(new Compositor.Layer)
  val regSortSecond = Reg(new Compositor.Layer)
  val regSortWindow = Reg(new PpuRegisters.WindowControl)
  when (io.enable && active) {
    val nextFirstLayer = WireDefault(regSortFirst)
    val nextSecondLayer = WireDefault(regSortSecond)

    val bgFifo = io.bgFifo(subCycle)
    val bgPriority = io.bgControl(subCycle).priority
    when (io.displayControl.enableBg(subCycle)) {
      bgFifo.ready := true.B
    }
    when (bgFifo.valid && bgFifo.bits.opaque && io.displayControl.enableBg(subCycle) && regSortWindow.bg(subCycle)) {
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

      // Evaluate windows
      val windowsEnabled = io.displayControl.displayWindow.orR || io.displayControl.objWindow
      val windowControl = Wire(new PpuRegisters.WindowControl)
      when (!windowsEnabled) {
        windowControl.blend := true.B
        windowControl.obj := true.B
        windowControl.bg := "b1111".U(4.W)
      } .elsewhen (io.displayControl.displayWindow(0) && win0ActiveX && win0ActiveY) {
        windowControl := io.win0Control
      } .elsewhen (io.displayControl.displayWindow(1) && win1ActiveX && win1ActiveY) {
        windowControl := io.win1Control
      } .elsewhen (io.displayControl.objWindow && io.objectData.window) {
        windowControl := io.winOutControl
      } .otherwise {
        windowControl := io.winOutControl
      }
      regSortWindow := windowControl

      // Set up the next set by fetching an object.
      io.objectRead := true.B
      io.objectIndex := fetchX
      regSortFirst.opaque := io.objectData.opaque && windowControl.obj
      regSortFirst.color := io.objectData.color
      regSortFirst.priority := io.objectData.priority
      regSortFirst.isBg := false.B
      regSortSecond.opaque := false.B
      fetchX := fetchX + 1.U
    } .otherwise {
      regSortFirst := nextFirstLayer
      regSortSecond := nextSecondLayer
    }
  }
}
