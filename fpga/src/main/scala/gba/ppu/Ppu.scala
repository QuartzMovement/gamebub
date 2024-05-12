package gba.ppu

import chisel3._
import chisel3.util._
import gba.MmioTarget
import gba.cpu.BusAccessWidth
import gba.mem.TargetInterface

class PpuOutput extends Bundle {
  /** Output pixel value (B G R) */
  val pixel = UInt(15.W)
  /** Whether the pixel this clock is valid */
  val valid = Bool()
  /** Whether the PPU is in hblank */
  val hblank = Bool()
  /** Whether the PPU is in vblank */
  val vblank = Bool()
}

class Ppu extends Module {
  val io = IO(new Bundle {
    /// Global enable
    val enable = Input(Bool())

    /// PPU output
    val output = Output(new PpuOutput)

    /// VRAM memory target for CPU
    val vramTarget = new TargetInterface(BusAccessWidth.Halfword)

    /// Palette memory target for CPU
    val paletteRamTarget = new TargetInterface(BusAccessWidth.Halfword)

    /// MMIO access
    val mmio = new MmioTarget()
  })

  /// VRAM: 96KiB, 16-bit access without byte strobe. Note: actually split into multiple banks for bg/obj
  val vram = Module(new Vram)
  vram.io.enable := io.enable
  vram.io.memTarget <> io.vramTarget

  val paletteRam = Module(new PpuMem(1024 / 2, BusAccessWidth.Halfword))
  paletteRam.io.enable := io.enable
  paletteRam.io.memTarget <> io.paletteRamTarget

  val scanline = RegInit(0.U(8.W))
  val tick = RegInit(0.U(11.W))

  when (io.enable) {
    when (tick < 1232.U) {
      tick := tick + 1.U
    } .otherwise {
      tick := 0.U
      when (scanline < 228.U) {
        scanline := scanline + 1.U
      } .otherwise {
        scanline := 0.U
      }
    }
  }

  io.output.hblank := tick >= 1006.U
  io.output.vblank := scanline >= 160.U

  // I/O registers
  io.mmio.valid := false.B
  io.mmio.dataRead := DontCare
  when (io.mmio.request) {
    // TODO Make better
    switch (io.mmio.address) {
      is ((0x4 / 4).U) {
        // DISPSTAT and VCOUNT
        io.mmio.valid := true.B
        io.mmio.dataRead := Cat(io.output.hblank, io.output.vblank)
      }
    }
  }

  // Background renderer
  val bgRender = Module(new BackgroundRenderer)
  bgRender.io.enable := io.enable
  bgRender.io.tick := tick
  bgRender.io.scanline := scanline
  bgRender.io.vram <> vram.io.portBG

  // Compositor
  val compositor = Module(new Compositor)
  compositor.io.enable := io.enable
  compositor.io.tick := tick
  compositor.io.scanline := scanline
  compositor.io.paletteRam <> paletteRam.io.ppuTarget
  compositor.io.bgFifo <> bgRender.io.pixels
  io.output.valid := compositor.io.valid
  io.output.pixel := compositor.io.pixel
}
