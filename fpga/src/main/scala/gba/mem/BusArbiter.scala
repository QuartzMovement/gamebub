package gba.mem

import chisel3._
import chisel3.util._

object BusArbiter {
  class RequestParams extends Bundle {
    val WRITE = Bool()
    val SIZE = BusAccessWidth()
    val PROT = new BusProtectionType
    val LOCK = Bool()
    val ADDR = UInt(32.W)
    val SEQ = Bool()
  }
}

/// A bus arbiter, coalescing requests from multiple input initiator ports to a single
/// output initiator port.
///
/// Uses strict priority.
class BusArbiter(numInputs: Int) extends Module {
  val io = IO(new Bundle {
    /// Global enable signal
    val enable = Input(Bool())

    /// Initiator ports
    val inputPorts = Vec(numInputs, Flipped(new BusInterface))

    /// Output port
    val outputPort = new BusInterface
  })

  // Bus is pipelined. In one bus cycle, we send addressing signals, and in the next, we send wdata / read rdata.
  // When a request comes in, choose highest priority request.
  // Note that since the input ports are pipelined too, by finishing the second part of a cycle, we're also
  // potentially allowing a new request to come in: we'll need to queue that up.

  io.outputPort.WRITE := DontCare
  io.outputPort.SIZE := DontCare
  io.outputPort.PROT := DontCare
  io.outputPort.LOCK := DontCare
  io.outputPort.ADDR := DontCare
  io.outputPort.MREQ := false.B
  io.outputPort.SEQ := false.B
  io.outputPort.WDATA := DontCare

  /// Whether a bus cycle is active.
  val busCycle = io.enable && io.outputPort.CLKEN
  /// Vector of which input ports have a memory request.
  val requestsVec = Wire(Vec(numInputs, Bool()))
  /// Vector of request params from each input port.
  val requestParamsVec = Wire(Vec(numInputs, new BusArbiter.RequestParams))
  /// Which input port is chosen for the next request.
  val requestChosen = Wire(Vec(numInputs, Bool()))
  /// Whether we started a memory request last bus cycle.
  val regRequested = RegInit(false.B)
  /// One-hot (or empty) vector of which input port the last request was for.
  val regRequestSource = RegInit(VecInit.fill(numInputs)(false.B))

  for ((port, i) <- io.inputPorts.zipWithIndex) {
    port.CLKEN := false.B
    port.ABORT := io.outputPort.ABORT
    port.RDATA := io.outputPort.RDATA

    val requestParams = Wire(new BusArbiter.RequestParams)
    requestParams.WRITE := port.WRITE
    requestParams.SIZE := port.SIZE
    requestParams.PROT := port.PROT
    requestParams.LOCK := port.LOCK
    requestParams.ADDR := port.ADDR
    requestParams.SEQ := port.SEQ

    val regRequestQueued = RegInit(false.B)
    val regQueuedParams = Reg(new BusArbiter.RequestParams)

    requestsVec(i) := port.MREQ || regRequestQueued
    requestParamsVec(i) := Mux(regRequestQueued, regQueuedParams, requestParams)

    when (busCycle) {
      // A request was completed.
      when (regRequested && regRequestSource(i)) {
        port.CLKEN := true.B

        // If this input port is requesting, but isn't chosen for the next request,
        // enabling CLKEN will ack its request, so we must queue the request.
        when (port.MREQ && !requestChosen(i)) {
          regRequestQueued := true.B
          regQueuedParams := requestParams
        }
      }

      // Our request was accepted.
      when (requestChosen(i)) {
        when (!regRequestQueued) {
          // Only CLKEN if we didn't already (when it was queued).
          port.CLKEN := true.B
        }
        regRequestQueued := false.B
      }
    }

    when (!port.MREQ && !(regRequested && regRequestSource(i)) && !regRequestQueued) {
      // When the initiator *isn't* requesting, allow it to proceed.
      // (e.g. internal cycles in CPU)
      port.CLKEN := true.B
    }
  }

  // Choose the next request.
  // TODO handle 'LOCK'
  val isRequesting = requestsVec.asUInt.orR
  requestChosen := PriorityEncoderOH(requestsVec)
  val requestChosenParams = suppressEnumCastWarning {
    Mux1H(requestChosen, requestParamsVec)
  }

  // Set the output bus parameters for the current request.
  when (isRequesting) {
    // TODO force non-sequential when changing initiators
    io.outputPort.MREQ := true.B
    io.outputPort.WRITE := requestChosenParams.WRITE
    io.outputPort.SIZE := requestChosenParams.SIZE
    io.outputPort.PROT := requestChosenParams.PROT
    io.outputPort.LOCK := requestChosenParams.LOCK
    io.outputPort.ADDR := requestChosenParams.ADDR
    io.outputPort.SEQ := requestChosenParams.SEQ
  }
  // Set WDATA based on the request from last bus cycle.
  io.outputPort.WDATA := Mux1H(regRequestSource, io.inputPorts.map(_.WDATA))

  when (busCycle) {
    when (isRequesting) {
      regRequested := true.B
      regRequestSource := requestChosen
    } .otherwise {
      regRequested := false.B
    }
  }
}
