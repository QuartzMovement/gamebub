package gba.apu

import chisel3._
import gba.MMIO

class AudioFifo extends Module {
  val io = IO(new Bundle {
    val writeEnable = Input(Bool())
    val writeData = Input(UInt(32.W))
    val writeMask = Input(UInt(4.W))

    val readEnable = Input(Bool())
    val readData = Output(UInt(8.W))
    val empty = Output(Bool())
    /// Whether the fifo has room for more entries (DMA request)
    val almostEmpty = Output(Bool())
  })

  val buffer = RegInit(VecInit.fill(8)(UInt(32.W)))
  /// Word-based write index
  val regWriteIndex = RegInit(0.U(3.W))
  /// *Byte*-based read index
  val regReadIndex = RegInit(0.U(5.W))
  val readIndexWord = regReadIndex(4, 2)

  when (io.readEnable && !io.empty) {
    regReadIndex := regReadIndex + 1.U
  }
  when (io.writeEnable) {
    buffer(regWriteIndex) := MMIO.mask(buffer(regWriteIndex), io.writeData, io.writeMask)
    regWriteIndex := regWriteIndex + 1.U
  }

  io.readData := buffer(readIndexWord).asTypeOf(Vec(4, UInt(8.W)))(regReadIndex(1, 0))
  io.empty := regWriteIndex === readIndexWord
  io.almostEmpty := !(regWriteIndex - readIndexWord)(2)
}
