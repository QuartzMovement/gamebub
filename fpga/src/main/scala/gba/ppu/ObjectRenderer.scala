package gba.ppu

import chisel3._
import chisel3.util._

class ObjectAttribute0 extends Bundle {
  val shape = UInt(2.W)
  val bpp8 = Bool()
  val mosaic = Bool()
  val effect = UInt(2.W)
  val double = Bool()
  val affine = Bool()
  val y = UInt(8.W)
}

class ObjectAttribute1 extends Bundle {
  val size = UInt(2.W)
  val flipY = Bool()
  val flipX = Bool()
  val affineIndexLo = UInt(3.W)
  val x = UInt(9.W)
}

class ObjectAttribute2 extends Bundle {
  val paletteBank = UInt(4.W)
  val priority = UInt(2.W)
  val tile = UInt(10.W)
}

class ObjectRenderer extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    val displayControl = Input(new PpuRegisters.DisplayControl)

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
