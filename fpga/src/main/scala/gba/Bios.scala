package gba

import chisel3._
import gba.mem.TargetInterface

class BiosRomAccess extends Bundle {
  val read = Output(Bool())
  val address = Output(UInt(12.W))
  val data = Input(UInt(32.W))
}

class Bios extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val target = new TargetInterface(32.W)
    val access = new BiosRomAccess
  })


  val readEnable = io.enable && io.target.request
  val readBusy = RegInit(false.B)
  io.target.done := false.B
  when (io.enable && readBusy) {
    io.target.done := true.B
    readBusy := false.B
  }
  when (readEnable) {
    readBusy := true.B
  }
  io.access.read := readEnable
  io.access.address := io.target.address >> 2
  io.target.dataRead := io.access.data

  // TODO handle lock/unlock
  // TODO handle open bus when not in range
}
