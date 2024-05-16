package gba.mem

import chisel3._
import chisel3.util._
import gba.cpu.BusAccessWidth

/// Simple ram that only supports single-cycle accesses of words (and smaller).
class SimpleRam(size: Int) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val target = new TargetInterface(32.W)
  })

  io.target.done := false.B

  val mem = SyncReadMem(size / 4, Vec(4, UInt(8.W)))

  val queuedWrite = RegInit(false.B)
  val queuedWriteAddress = Reg(UInt(log2Ceil(size / 4).W))
  val queuedWriteMask = Reg(UInt(4.W))
  when (io.enable && queuedWrite) {
    mem.write(queuedWriteAddress, io.target.dataWrite.asTypeOf(Vec(4, UInt(8.W))), queuedWriteMask.asBools)

    queuedWrite := false.B
    io.target.done := true.B
  }
  when (io.enable && io.target.request && io.target.write) {
    queuedWrite := true.B
    queuedWriteAddress := io.target.address >> 2
    queuedWriteMask := io.target.mask
  }

  val readEnable = io.enable && io.target.request && !io.target.write
  val readBusy = RegInit(false.B)
  io.target.dataRead := mem.read(io.target.address >> 2, readEnable).asUInt
  when (io.enable && readBusy) {
    io.target.done := true.B
    readBusy := false.B
  }
  when (readEnable) {
    readBusy := true.B
  }
}
