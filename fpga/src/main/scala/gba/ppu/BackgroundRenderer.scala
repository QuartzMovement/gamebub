package gba.ppu

import chisel3._
import chisel3.util._
import gba.cpu.BusAccessWidth

class BackgroundPixel extends Bundle {
  // These should be palette indices (8-bit) -- layer 2 in mode 3 and 5 is 16-bit color, can just pack into L3 too.

  val valid = Vec(4, Bool())
  val color = Vec(4, UInt(8.W))
}

class BackgroundRenderer extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    /// BG VRAM access
    val vram = Flipped(new PpuMemoryInterface(96 * 1024 / 2, 16.W))

    /// Current cycle in the scanline
    val tick = Input(UInt(11.W))
    val scanline = Input(UInt(8.W))

    /// Pixel fifo dequeue interface
    val pixels = DecoupledIO(new BackgroundPixel)
  })

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
  when (layerActive(2)) {
    when (subFetch === 3.U) {
      io.vram.read := true.B
      io.vram.address := ((io.scanline * 240.U) + layerPos(2)) >> 1
//      printf(cf"[BG] fetch ${io.vram.address}%x | tick=${io.tick} | scan=${io.scanline} p=${layerPos(2)}  \n")


      // TODO: !!!! it doesn't seem to be reading the right data. Either it's not getting written or we're not reading it properly somehow
      // note: it's getting written fine (checked with simulator.cpp)
      // note: removing the 'read enable' parameter from mem.read in Vram seems to fix it?
    }
    when (subUse === 3.U) {
      layerPos(2) := layerPos(2) + 1.U
      fifo.bits.valid(2) := true.B
      fifo.bits.color(2) := Mux(
        layerPos(2)(0) === 0.U,
        io.vram.readData(7, 0), io.vram.readData(15, 8)
      )
      fifo.valid := true.B
//      printf(cf"[BG] inserting pix ${io.vram.readData}\n")
    }
  }

  when (io.enable && io.scanline < 160.U) {
    when (io.tick === 30.U) {
      /// Assume BG 2 is active
      /// TODO fix
      layerActive(2) := true.B
//      printf(cf"[BG] Enable 2\n")
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
