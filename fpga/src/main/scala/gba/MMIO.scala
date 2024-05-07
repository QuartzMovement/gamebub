package gba

import chisel3._
import chisel3.util._
import gba.cpu.BusAccessWidth
import gba.mem.TargetInterface

class MMIO extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val target = new TargetInterface(BusAccessWidth.Word)
  })

  io.target.dataRead := 0.U
  io.target.done := true.B
}
