package gba.mem

import chisel3._
import chisel3.util._
import gba.cpu.{BusInterface, BusAccessWidth}

class TargetInterface(maxWidth: BusAccessWidth.Type) extends Bundle {
  val address = Input(UInt(25.W))
  val enable = Input(Bool())
  val sequential = Input(Bool())
  val write = Input(Bool())
  val size = Input(BusAccessWidth())
  val dataWrite = Input(UInt(BusAccessWidth.toWidth(maxWidth)))
  val dataRead = Output(UInt(BusAccessWidth.toWidth(maxWidth)))
  val valid = Output(Bool())
}

class Bus(
  /// Tuples of (4 bit memory prefix, bus width)
  targets: Seq[(UInt, BusAccessWidth.Type)],
) extends Module {
  val io = IO(new Bundle {
    /// Global enable signal
    val enable = Input(Bool())

    /// CPU initiator port
    val initiatorPort = Flipped(new BusInterface)

    /// Target ports
    val targetPort = MixedVec(targets.map(t => Flipped(new TargetInterface(t._2))))
  })

  // Memory bus is pipelined:
  // At each rising clock edge (when CLKEN is 1), ADDR/TRANS/WRITE/SIZE are broadcast
  // to initiate an access, and RDATA/WDATA from the previous access are sampled.
  io.initiatorPort.CLKEN := true.B
  io.initiatorPort.ABORT := false.B
  io.initiatorPort.RDATA := DontCare

  for ((target, i) <- io.targetPort.zipWithIndex) {
    val (prefix, width) = targets(i)
    val selected = io.initiatorPort.ADDR(27, 27 - prefix.getWidth + 1) === prefix

    // TODO
    target.address := DontCare
    target.enable := DontCare
    target.sequential := DontCare
    target.write := DontCare
    target.size := DontCare
    target.dataWrite := DontCare
  }
}
