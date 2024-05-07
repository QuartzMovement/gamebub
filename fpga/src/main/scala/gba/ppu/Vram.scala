package gba.ppu

import chisel3._
import gba.cpu.BusAccessWidth
import gba.mem.TargetInterface

class Vram extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    /// Target interface for main CPU memory bus
    val memTarget = new TargetInterface(BusAccessWidth.Halfword)
  })

  /// VRAM: 96KiB, 16-bit access without byte strobe.
  ///
  /// TODO: Note: actually split into multiple banks for bg/obj
  val mem = SyncReadMem(96 * 1024 / 2, UInt(16.W))


  // CPU access
  {
    io.memTarget.done := false.B
    val queuedWrite = RegInit(false.B)
    val queuedWriteAddress = Reg(UInt(17.W))
    when (io.enable && queuedWrite) {
      mem.write(queuedWriteAddress >> 1, io.memTarget.dataWrite)
      queuedWrite := false.B
      io.memTarget.done := true.B
    }
    when (io.enable && io.memTarget.request && io.memTarget.write) {
      queuedWrite := true.B
      queuedWriteAddress := io.memTarget.address
    }

    val readEnable = io.enable && io.memTarget.request && !io.memTarget.write
    val readBusy = RegInit(false.B)
    io.memTarget.dataRead := mem.read(io.memTarget.address >> 1, readEnable).asUInt
    when (io.enable && readBusy) {
      io.memTarget.done := true.B
      readBusy := false.B
    }
    when (readEnable) {
      readBusy := true.B
    }
  }
}
