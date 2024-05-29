package gba.ppu

import chisel3._
import chisel3.util._

class ObjectRenderer extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    /// OBJ VRAM access
    val vram = Flipped(new PpuMemoryInterface(32 * 1024 / 2, 16.W))

    /// OAM access
    val oam = Flipped(new PpuMemoryInterface(1024 / 4, 32.W))

    /// Current cycle in the scanline
    val tick = Input(UInt(11.W))
    val scanline = Input(UInt(8.W))
  })

  io.vram.read := false.B
  io.vram.address := DontCare

  io.oam.read := false.B
  io.oam.address := DontCare
}
