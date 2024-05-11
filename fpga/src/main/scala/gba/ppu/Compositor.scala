package gba.ppu

import chisel3._
import chisel3.util._

class Compositor extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val tick = Input(UInt(11.W))

    val valid = Output(Bool())
    val pixel = Output(UInt(15.W))

    val bgFifo = Flipped(DecoupledIO(new BackgroundPixel))
  })

  val scanlineX = Reg(UInt(8.W))
  when (io.enable && io.tick === 0.U) {
    scanlineX := 0.U
  }

  io.valid := false.B
  io.pixel := DontCare

  io.bgFifo.ready := false.B
  when (io.enable && scanlineX < 240.U && io.bgFifo.valid) {
    // Temporary
    io.valid := true.B
    io.pixel := Fill(15, io.bgFifo.bits.color(2)(0))
    io.bgFifo.ready := true.B
    scanlineX := scanlineX + 1.U

//    printf(cf"[Comp] pixel out at tick=${io.tick}\n")
  }
}
