package gba.ppu

import chisel3._
import chisel3.util._

class BackgroundPixel extends Bundle {
  // These should be palette indices (8-bit) -- layer 2 in mode 3 and 5 is 16-bit color, can just pack into L3 too.

  val valid = Vec(4, Bool())
  val color = Vec(4, UInt(8.W))
}

class BackgroundRenderer extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    val displayControl = Input(new PpuRegisters.DisplayControl)

    /// BG VRAM access
    val vram = Flipped(new PpuMemoryInterface(96 * 1024 / 2, 16.W))

    /// Current cycle in the scanline
    val tick = Input(UInt(11.W))
    val scanline = Input(UInt(8.W))

    /// Pixel fifo dequeue interface
    val pixels = DecoupledIO(new BackgroundPixel)
  })
  val isBitmapMode = io.displayControl.mode >= 3.U

  // Output pixel FIFO
  val fifo = Wire(EnqIO(new BackgroundPixel))
  fifo.valid := false.B
  fifo.bits.valid := VecInit(Seq.fill(4)(false.B))
  fifo.bits.color := DontCare
  val fifoFlush = WireDefault(false.B)
  io.pixels <> Queue(fifo, entries = 5, flush = Some(fifoFlush))

  /// Whether each layer is active during this part of the line.
  val layerActive = Reg(Vec(4, Bool()))
  val layerPos = Reg(Vec(4, UInt(8.W)))

  // Index within a subpixel (0..3) that is being fetched, then is being used.
  val subFetch = io.tick(1, 0) + 1.U
  val subUse = io.tick(1, 0) + 2.U

  // TODO: this is hardcoded to 8bpp bitmap
  io.vram.read := false.B
  io.vram.address := DontCare

  when (isBitmapMode && layerActive(2)) {
    when (subFetch === 3.U) {
      io.vram.read := true.B
      val frameOffset = Mux(io.displayControl.frame === 1.U, (0xA000 / 2).U, 0.U)
      switch (io.displayControl.mode) {
        is (3.U) {
          // 240x160, 16bpp
          io.vram.address := ((io.scanline * 240.U) + layerPos(2))
        }
        is (4.U) {
          // 240x160, indexed 8bpp
          io.vram.address := (((io.scanline * 240.U) + layerPos(2)) >> 1).asUInt.pad(16) + frameOffset
        }
        is (5.U) {
          // 160x128, 16bpp
          io.vram.address := ((io.scanline * 160.U) + layerPos(2)) + frameOffset
        }
      }
//      printf(cf"[BG] fetch ${io.vram.address}%x | tick=${io.tick} | scan=${io.scanline} p=${layerPos(2)}  \n")
    }
    when (subUse === 3.U) {
      layerPos(2) := layerPos(2) + 1.U
      fifo.bits.valid(2) := true.B
      fifo.valid := true.B

      switch (io.displayControl.mode) {
        is (4.U) {
          fifo.bits.color(2) := Mux(
            layerPos(2)(0) === 0.U,
            io.vram.readData(7, 0), io.vram.readData(15, 8)
          )
        }
        is (3.U, 5.U) {
          fifo.bits.color(2) := io.vram.readData(7, 0)
          fifo.bits.color(3) := io.vram.readData(15, 8)
          // TODO handle mode 5 OOB
        }
      }
//      printf(cf"[BG] inserting pix ${io.vram.readData}\n")
    }
  }

  when (io.enable && io.scanline < 160.U) {
    when (isBitmapMode) {
      when (io.tick === 30.U && io.displayControl.enableBg(2)) {
        layerActive(2) := true.B
      }
    }
    when (io.tick === 1005.U) {
      // Begin HBlank
//      printf(cf"[BG] hblank for ${io.scanline}\n")
      layerActive := VecInit(Seq.fill(4)(false.B))
      layerPos := VecInit(Seq.fill(4)(0.U))
      fifoFlush := true.B
    }
  }
}
