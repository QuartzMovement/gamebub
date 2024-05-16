package gba.ppu

import chisel3._
import chisel3.util._
import gba.cpu.BusAccessWidth
import gba.mem.TargetInterface

class PpuMemoryInterface(size: Int, width: Width) extends Bundle {
  /// Word address
  val address = Input(UInt(log2Ceil(size).W))
  val read = Input(Bool())
  val readData = Output(UInt(width))
}

/// Module for PPU-owned memory that is also exposed to the CPU:
/// VRAM, OAM, and Palette RAM
///
/// PPU port is always read-only and takes priority over CPU.
/// Byte strobe is not supported, but halfword writes are
class PpuMem(size: Int, width: Width) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    /// Target interface for main CPU memory bus
    val memTarget = new TargetInterface(width)

    /// Target interface for the PPU
    val ppuTarget = new PpuMemoryInterface(size, width)
  })

  val mem = SyncReadMem(size, UInt(width))

  // CPU access
  {
    io.memTarget.done := false.B

    // Write
    val queuedWrite = RegInit(false.B)
    val queuedWriteAddress = Reg(UInt((log2Ceil(size) + 2).W))
    when (io.enable && queuedWrite) {
      // TODO support halfword strobe if width > halfword (OAM)
      mem.write(queuedWriteAddress >> 1, io.memTarget.dataWrite)
      queuedWrite := false.B
      io.memTarget.done := true.B

//      printf(cf" [vram] bg write: ${queuedWriteAddress >> 1}%x --- data ${io.memTarget.dataWrite}%x\n")
    }
    when (io.enable && io.memTarget.request && io.memTarget.write) {
      queuedWrite := true.B
      queuedWriteAddress := io.memTarget.address
    }

    // Read
    val readEnable = io.enable && io.memTarget.request && !io.memTarget.write && !io.ppuTarget.read
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

  // PPU access
  // TODO: make sure there's a single port -- (or maybe one write port and one read port)
  io.ppuTarget.readData := mem.read(io.ppuTarget.address) //io.ppuTarget.read)
  // TODO: ^^ adding the read enable parameter breaks it? always reads 0? check how this works
}

