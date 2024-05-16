package gba.ppu

import chisel3._
import chisel3.util._
import gba.cpu.BusAccessWidth

class Compositor extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val tick = Input(UInt(11.W))
    val scanline = Input(UInt(8.W))

    val paletteRam = Flipped(new PpuMemoryInterface(1024 / 2, 16.W))

    val valid = Output(Bool())
    val pixel = Output(UInt(15.W))

    val bgFifo = Flipped(DecoupledIO(new BackgroundPixel))
  })

  io.bgFifo.ready := false.B
  io.paletteRam.read := false.B
  io.paletteRam.address := DontCare

  val outX = Reg(UInt(8.W))
  val active = Reg(Bool())
  when (io.enable) {
    when (io.tick === 0.U) {
      outX := 0.U
    }
    when (io.tick === 45.U && io.scanline < 160.U) {
      active := true.B
    }
    when (outX === 240.U) {
      active := false.B
    }
  }

  val subCycle = (io.tick - 2.U)(1, 0)

  io.valid := false.B
  io.pixel := DontCare
  when (io.enable && active) {
    when (subCycle === 0.U) {
      io.paletteRam.read := true.B
      io.paletteRam.address := io.bgFifo.bits.color(2) // Temporary
      io.bgFifo.ready := true.B

//      printf(cf"[Comp] pixel out at tick=${io.tick}\n")
    }
    when (subCycle === 1.U) {
      io.valid := true.B
      io.pixel := io.paletteRam.readData
      outX := outX + 1.U
    }
  }
}
