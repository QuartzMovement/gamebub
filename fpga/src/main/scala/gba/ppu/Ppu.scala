package gba.ppu

import chisel3._
import chisel3.util._
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
  })

  /// VRAM: 96KiB, 16-bit access without byte strobe. Note: actually split into multiple banks for bg/obj
  val vram = Module(new Vram)
  vram.io.enable := io.enable
  vram.io.memTarget <> io.vramTarget

  val scanline = RegInit(0.U(8.W))
  val cycle = RegInit(0.U(11.W))

  when (io.enable) {
    when (cycle < 1232.U) {
      cycle := cycle + 1.U
    } .otherwise {
      cycle := 0.U
      when (scanline < 228.U) {
        scanline := scanline + 1.U
      } .otherwise {
        scanline := 0.U
      }
    }
  }

  io.output.hblank := cycle >= 960.U
  io.output.vblank := scanline >= 160.U
  io.output.valid := (cycle(1, 0) === 3.U) && !io.output.hblank && !io.output.vblank
  io.output.pixel := Cat(cycle(6, 2), scanline(4, 0))
}
