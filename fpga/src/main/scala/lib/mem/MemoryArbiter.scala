package lib.mem

import chisel3._
import chisel3.util._

/**
 * Allows multiple initiator MemoryInterfaces to map to one target MemoryInterface.
 * Priority is given to the lowest initiator.
 */
class MemoryArbiter(addressWidth: Int, dataWidth: Int, n: Int) extends Module {
  val io = IO(new Bundle {
    val target = Flipped(new MemoryInterface(addressWidth, dataWidth))
    val initiator = Vec(n, new MemoryInterface(addressWidth, dataWidth))
  })

  /** Whether we're currently waiting for an access. */
  val busy = RegInit(false.B)
  /** If busy, the index of the initiator who initiated the access. */
  val busyOwner = Reg(UInt(log2Ceil(n).W))

  for (i <- 0 until n) {
    io.initiator(i).done := false.B
    io.initiator(i).dataRead := io.target.dataRead
  }

  when (busy) {
    io.target.address := io.initiator(busyOwner).address
    io.target.enable := io.initiator(busyOwner).enable
    io.target.write := io.initiator(busyOwner).write
    io.target.dataWrite := io.initiator(busyOwner).dataWrite
    io.target.writeStrobe := io.initiator(busyOwner).writeStrobe

    when (io.target.done) {
      io.initiator(busyOwner).done := true.B
      busy := false.B
    }
  } .otherwise {
    io.target.address := DontCare
    io.target.enable := false.B
    io.target.write := false.B
    io.target.dataWrite := DontCare
    io.target.writeStrobe := DontCare

    val hasRequest = WireDefault(false.B)
    val chosen = WireDefault(0.U(log2Ceil(n).W))

    for (i <- n - 1 to 0 by -1) {
      when (io.initiator(i).enable) {
        chosen := i.asUInt
        hasRequest := true.B

        io.target.address := io.initiator(i).address
        io.target.enable := true.B
        io.target.write := io.initiator(i).write
        io.target.dataWrite := io.initiator(i).dataWrite
        io.target.writeStrobe := io.initiator(i).writeStrobe
      }
    }

    when (hasRequest) {
      when (io.target.done) {
        io.initiator(chosen).done := true.B
      } .otherwise {
        busy := true.B
        busyOwner := chosen
      }
    }
  }

  /*
  Start not busy.

  If not busy,
    find the highest priority initiator requesting access
      If none, do nothing
      Pass that access on to the target,
        and if (by the next cycle), the target is not "done", mark ourselves as busy

  If busy and the target is "done",
    mark the initiator that started as "done".
    and set ourselves to not busy
  */
}