package gba.ppu

import chisel3._
import gba.cpu.BusAccessWidth
import gba.mem.TargetInterface

class PpuMemoryInterface(width: Width) extends Bundle {
  /// Word address
  val address = Input(UInt(16.W))
  val read = Input(Bool())
  val readData = Output(UInt(width))
}

class Vram extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    /// Target interface for main CPU memory bus
    val memTarget = new TargetInterface(BusAccessWidth.Halfword)

    /// PPU read interface for Backgrounds
    val portBG = new PpuMemoryInterface(16.W)
  })

  /// VRAM: 96KiB, 16-bit access without byte strobe.
  ///
  /// TODO: Note: actually split into multiple banks for bg/obj
  val mem = SyncReadMem(96 * 1024 / 2, UInt(16.W))

  // CPU access
  {
    io.memTarget.done := false.B

    // Write
    val queuedWrite = RegInit(false.B)
    val queuedWriteAddress = Reg(UInt(17.W))
    when (io.enable && queuedWrite) {
      mem.write(queuedWriteAddress >> 1, io.memTarget.dataWrite)
      queuedWrite := false.B
      io.memTarget.done := true.B

      printf(cf" [vram] bg write: ${queuedWriteAddress >> 1}%x --- data ${io.memTarget.dataWrite}%x\n")
    }
    when (io.enable && io.memTarget.request && io.memTarget.write) {
      queuedWrite := true.B
      queuedWriteAddress := io.memTarget.address
    }

    // Read
    val readEnable = io.enable && io.memTarget.request && !io.memTarget.write && !io.portBG.read
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

  // BG PPU access
  // TODO: make sure there's a single port -- (or maybe one write port and one read port)
  io.portBG.readData := mem.read(io.portBG.address) //io.portBG.read)
  // TODO: ^^ adding the read enable parameter breaks it? always reads 0? check how this works
  when (io.portBG.read) {
//    printf(cf" [vram] bg read: ${io.portBG.address}%x --- data ${io.portBG.readData}%x\n")
  }
}
