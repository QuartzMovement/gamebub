package gba.ppu

import chisel3._

object PpuRegisters {
  class DisplayControl extends Bundle {
    val greenSwap = Bool()
    val objWindow = Bool()
    val displayWindow = UInt(2.W)
    val enableObj = Bool()
    val enableBg = UInt(4.W)
    val forceBlank = Bool() // TODO
    val objMapping = UInt(1.W)
    val hblankFree = Bool()
    val frame = UInt(1.W)
    val cgbMode = Bool()  // TODO only settable in BIOS
    val mode = UInt(3.W)
  }

  class DisplayStatus extends Bundle {
    val scanline = UInt(8.W)

    val vcount = UInt(8.W)

    val _padding = UInt(2.W)
    val irqVcount = Bool()
    val irqHblank = Bool()
    val irqVblank = Bool()
    val vcountHit = Bool()
    val hblank = Bool()
    val vblank = Bool()
  }
}
