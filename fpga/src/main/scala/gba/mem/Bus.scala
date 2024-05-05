package gba.mem

import chisel3._
import chisel3.util._
import gba.cpu.{BusAccessWidth, BusInterface, BusTransactionType}

class TargetInterface(maxWidth: BusAccessWidth.Type) extends Bundle {
  val address = Input(UInt(25.W))
  val request = Input(Bool())
  val sequential = Input(Bool())
  val write = Input(Bool())
  val size = Input(BusAccessWidth())
  val dataWrite = Input(UInt(BusAccessWidth.toWidth(maxWidth)))
  val dataRead = Output(UInt(BusAccessWidth.toWidth(maxWidth)))
  val done = Output(Bool())
}

case class BusTarget(
  name: String,
  prefix: UInt,
  dataWidth: BusAccessWidth.Type,
)

class Bus(
  targets: Seq[BusTarget],
) extends Module {
  val io = IO(new Bundle {
    /// Global enable signal
    val enable = Input(Bool())

    /// CPU initiator port
    val initiatorPort = Flipped(new BusInterface)

    /// Target ports
    val targetPort = MixedVec(targets.map(t => Flipped(new TargetInterface(t.dataWidth))))
  })
  val regCurrentAddress = Reg(UInt(28.W))
  val regCurrentSize = Reg(BusAccessWidth())
  val regCurrentWrite = Reg(Bool())
  val regIsBusy = RegInit(false.B)

  /// Whether a request is completing.
  val isDone = WireDefault(false.B)
  /// Whether we can accept a new request.
  val isAvailable = !regIsBusy || isDone
  /// Whether there is an incoming request.
  val isRequested = io.initiatorPort.TRANS === BusTransactionType.Sequential || io.initiatorPort.TRANS === BusTransactionType.NonSequential
  /// Whether a new request is being accepted.
  val isAccepted = io.enable && isAvailable && isRequested

  // Align addresses
  val initiatorAddress = Wire(UInt(28.W))
  when (io.initiatorPort.SIZE === BusAccessWidth.Word) {
    initiatorAddress := Cat(io.initiatorPort.ADDR(31, 2), 0.U(2.W))
  } .elsewhen (io.initiatorPort.SIZE === BusAccessWidth.Halfword) {
    initiatorAddress := Cat(io.initiatorPort.ADDR(31, 1), 0.U(1.W))
  } .otherwise {
    initiatorAddress := io.initiatorPort.ADDR
  }

  printf(cf"done=$isDone, avail=$isAvailable, req=$isRequested, accept=$isAccepted   (addr=${initiatorAddress}%x), trans=${io.initiatorPort.TRANS}\n")

  when (io.enable) {
    when (isAccepted) {
      regIsBusy := true.B
      regCurrentAddress := initiatorAddress
      regCurrentSize := io.initiatorPort.SIZE
      regCurrentWrite := io.initiatorPort.WRITE
    } .elsewhen (isDone) {
      regIsBusy := false.B
    }
  }

  // Memory bus is pipelined:
  // At each rising clock edge (when CLKEN is 1), ADDR/TRANS/WRITE/SIZE are broadcast
  // to initiate an access, and RDATA/WDATA from the previous access are sampled.
  io.initiatorPort.CLKEN := isAvailable
  io.initiatorPort.ABORT := false.B
  io.initiatorPort.RDATA := DontCare

  // TODO turn 32-bit accesses into 16-bit accesses if needed

  for ((target, i) <- io.targetPort.zipWithIndex) {
    val metadata  = targets(i)
    val nextSelected = initiatorAddress(27, 27 - metadata.prefix.getWidth + 1) === metadata.prefix
    val currentSelected = regCurrentAddress(27, 27 - metadata.prefix.getWidth + 1) === metadata.prefix

    target.address := initiatorAddress
    target.request := false.B
    target.sequential := false.B // TODO
    target.write := io.initiatorPort.WRITE
    target.size := io.initiatorPort.SIZE
    target.dataWrite := io.initiatorPort.WDATA

    when (isAccepted && nextSelected) {
      // Accept a new request.
      printf(cf"[Bus] start request for '${metadata.name}' ($i)  addr=0x${initiatorAddress}%x\n")
      target.request := true.B
    }
    when (currentSelected && regIsBusy) {
      when (target.done) {
        printf(cf"[Bus] complete request for '${metadata.name}' ($i)  addr=0x${regCurrentAddress}%x data=0x${target.dataRead}%x\n")
        isDone := true.B
        io.initiatorPort.RDATA := target.dataRead
      }
    }
  }
}
