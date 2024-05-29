package gba.ppu

import chisel3._
import gba.mem.TargetInterface

class Vram extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    /// Target interface for main CPU memory bus
    val memTarget = new TargetInterface(16.W)

    /// PPU read interface for Backgrounds
    val portBG = new PpuMemoryInterface(96 * 1024 / 2, 16.W)

    /// PPU read interface for Objects
    val portOBJ = new PpuMemoryInterface(32 * 1024 / 2, 16.W)
  })

  /// VRAM: 96KiB, 16-bit access without byte strobe.
  val mem = Module(new PpuMem(96 * 1024 / 2, 16.W))
  mem.io.enable := io.enable
  mem.io.memTarget <> io.memTarget
  /// TODO: Note: actually split into multiple banks for bg/obj
  mem.io.ppuTarget <> io.portBG

  io.portOBJ.readData := 0.U // TODO
}
