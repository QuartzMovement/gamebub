package gba.mem

import chisel3._
import chisel3.util._

/// Simple ram that only supports single-cycle accesses of words (and smaller).
class SimpleRam(name: String, size: Int, width: Width, waitStates: Int = 0) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val target = new TargetInterface(width)
  })
  val widthBytes = width.get / 8
  val numWords = size / widthBytes
  val addrShift = log2Ceil(widthBytes)

  val busy = RegInit(false.B)
  val busyCounter = Reg(UInt(log2Ceil(waitStates + 1).W))
  val queuedWrite = RegInit(false.B)
  val queuedWriteAddress = Reg(UInt(log2Ceil(numWords).W))
  val queuedWriteMask = Reg(UInt(widthBytes.W))

  // Note that, even though we ostensibly do *either* only one read *or* one write each cycle,
  // due to the pipelined bus, a read access must be able to start when we're *finishing* (i.e.
  // actually performing) the previous write access. Thus, we need two separate read and
  // write ports.
  val mem = SRAM.masked(numWords, Vec(widthBytes, UInt(8.W)), numReadPorts = 1, numWritePorts = 1, numReadwritePorts = 0)
  val memReadPort = mem.readPorts(0)
  val memWritePort = mem.writePorts(0)
  memReadPort.enable := false.B
  memReadPort.address := DontCare
  memWritePort.enable := false.B
  memWritePort.address := DontCare
  memWritePort.mask.get := DontCare
  memWritePort.data := DontCare
  io.target.done := false.B
  io.target.dataRead := DontCare

  when (io.enable) {
    when (busy) {
      busyCounter := busyCounter - 1.U

      when (busyCounter === 0.U) {
        busy := false.B
        io.target.done := true.B

        when (queuedWrite) {
          memWritePort.enable := true.B
          memWritePort.address := queuedWriteAddress
          memWritePort.mask.get := queuedWriteMask.asBools
          memWritePort.data := io.target.dataWrite.asTypeOf(Vec(widthBytes, UInt(8.W)))
        } .otherwise {
          io.target.dataRead := memReadPort.data.asUInt
        }
      }
    }
    when (io.target.request && !(busy && busyCounter > 0.U)) {
      busy := true.B
      busyCounter := waitStates.U
      queuedWrite := io.target.write
      when (io.target.write) {
        queuedWriteAddress := io.target.address >> addrShift
        queuedWriteMask := io.target.mask
      } .otherwise {
        memReadPort.enable := true.B
        memReadPort.address := io.target.address >> addrShift
      }
    }
  }
}
