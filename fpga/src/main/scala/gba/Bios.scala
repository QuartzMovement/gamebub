package gba

import chisel3._
import chisel3.util._
import gba.cpu.BusAccessWidth
import gba.mem.TargetInterface

class Bios extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val target = new TargetInterface(BusAccessWidth.Word)
  })

  val rom = SyncReadMem(16 * 1024 / 4, UInt(32.W))

  // dontTouch: hack to ensure Chisel doesn't optimize the mem out
  val temp = dontTouch(WireDefault(false.B))
  when (temp) {
    rom.write(0.U, 0.U)
  }

  val readEnable = io.enable && io.target.request
  val readBusy = RegInit(false.B)
  io.target.done := false.B
  io.target.dataRead := rom.read(io.target.address >> 2, readEnable).asUInt
  when (io.enable && readBusy) {
    io.target.done := true.B
    readBusy := false.B
  }
  when (readEnable) {
    readBusy := true.B
  }

  // TODO handle lock/unlock
  // TODO handle open bus when not in range
}
