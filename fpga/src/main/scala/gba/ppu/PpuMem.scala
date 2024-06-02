package gba.ppu

import chisel3._
import chisel3.util._
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
  val widthHalfwords = width.get / 16

  val mem = SyncReadMem(size, Vec(widthHalfwords, UInt(16.W)))

  // CPU access
  {
    io.memTarget.done := false.B

    // Write
    val queuedWrite = RegInit(false.B)
    val queuedWriteAddress = Reg(UInt((log2Ceil(size) + 2).W))
    val queuedWriteMask = Reg(UInt((width.get / 8).W))
    when (io.enable && queuedWrite) {
      val mask = if (width == 32.W) {
        // TODO verify this is the correct 8-bit write behavior for OAM. It might just ignore 8-bit writes?
        Seq(queuedWriteMask(0) || queuedWriteMask(1), queuedWriteMask(2) || queuedWriteMask(3))
      } else {
        Seq(true.B)
      }

      val shift = log2Ceil(width.get / 8)
      mem.write(queuedWriteAddress >> shift, io.memTarget.dataWrite.asTypeOf(Vec(widthHalfwords, UInt(16.W))), mask)
      queuedWrite := false.B
      io.memTarget.done := true.B

//      printf(cf" [vram] bg write: ${queuedWriteAddress >> 1}%x --- data ${io.memTarget.dataWrite}%x\n")
    }
    when (io.enable && io.memTarget.request && io.memTarget.write) {
      queuedWrite := true.B
      queuedWriteAddress := io.memTarget.address
      queuedWriteMask := io.memTarget.mask
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
  io.ppuTarget.readData := mem.read(io.ppuTarget.address, io.ppuTarget.read).asUInt
}

