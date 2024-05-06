package gba.mem

import chisel3._
import gba.cpu.BusAccessWidth

/// Simple ram that only supports single-cycle accesses of words (and smaller).
class SimpleRam(size: Int) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val target = new TargetInterface(BusAccessWidth.Word)
  })

  io.target.done := false.B

  val mem = SyncReadMem(size / 4, Vec(4, UInt(8.W)))

  val queuedWrite = RegInit(false.B)
  val queuedWriteAddress = Reg(UInt(25.W))
  val queuedWriteSize = Reg(BusAccessWidth())
  when (io.enable && queuedWrite) {
    import BusAccessWidth._
    val size = queuedWriteSize
    val address = queuedWriteAddress

    // When converting vecs to/from uint, element 0 is the low byte.
    val mask = Seq(
      (size === Word) || (size === Halfword && address(1) === 0.U) || (size === Byte && address(1, 0) === 0.U),
      (size === Word) || (size === Halfword && address(1) === 0.U) || (size === Byte && address(1, 0) === 1.U),
      (size === Word) || (size === Halfword && address(1) === 1.U) || (size === Byte && address(1, 0) === 2.U),
      (size === Word) || (size === Halfword && address(1) === 1.U) || (size === Byte && address(1, 0) === 3.U),
    )
    mem.write(queuedWriteAddress >> 2, io.target.dataWrite.asTypeOf(Vec(4, UInt(8.W))), mask)

    queuedWrite := false.B
    io.target.done := true.B
  }
  when (io.enable && io.target.request && io.target.write) {
    queuedWrite := true.B
    queuedWriteAddress := io.target.address
    queuedWriteSize := io.target.size
  }

  val readEnable = io.enable && io.target.request && !io.target.write
  val readBusy = RegInit(false.B)
  io.target.dataRead := mem.read(queuedWriteAddress >> 2, readEnable).asUInt
  when (io.enable && readBusy) {
    io.target.done := true.B
    readBusy := false.B
  }
  when (readEnable) {
    readBusy := true.B
  }
}
