package gba.mem

import chisel3._
import chisel3.util._
import gba.cpu.BusAccessWidth

/// Simple ram that only supports single-cycle accesses of words (and smaller).
class SimpleRam(size: Int, width: Width) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val target = new TargetInterface(width)
  })
  val widthBytes = width.get / 8
  val numWords = size / widthBytes

  io.target.done := false.B

  val mem = SyncReadMem(numWords, Vec(widthBytes, UInt(8.W)))

  val queuedWrite = RegInit(false.B)
  val queuedWriteAddress = Reg(UInt(log2Ceil(numWords).W))
  val queuedWriteMask = Reg(UInt(widthBytes.W))
  when (io.enable && queuedWrite) {
    printf(cf"  write data=0x${io.target.dataWrite}%x  mask=${queuedWriteMask} to: 0x${queuedWriteAddress}%x\n")
    mem.write(queuedWriteAddress, io.target.dataWrite.asTypeOf(Vec(widthBytes, UInt(8.W))), queuedWriteMask.asBools)

    queuedWrite := false.B
    io.target.done := true.B
  }
  when (io.enable && io.target.request && io.target.write) {
    queuedWrite := true.B
    queuedWriteAddress := io.target.address >> log2Ceil(widthBytes)
    queuedWriteMask := io.target.mask
    printf(cf"start write: addr=0x${io.target.address}%x mask=${io.target.mask}%b\n")
  }

  val readEnable = io.enable && io.target.request && !io.target.write
  val readBusy = RegInit(false.B)
  io.target.dataRead := mem.read(io.target.address >> log2Ceil(widthBytes)).asUInt
  when (io.enable && readBusy) {
    printf(cf"  read data=0x${io.target.dataRead}%x\n")
    io.target.done := true.B
    readBusy := false.B
  }
  when (readEnable) {
    printf(cf"start read: addr=0x${io.target.address}%x\n")
    readBusy := true.B
  }
}
