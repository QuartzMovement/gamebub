package gba

import chisel3._
import chisel3.util._
import gba.cpu.BusAccessWidth
import gba.mem.TargetInterface

class MmioTarget extends Bundle {
  /// *Word* address
  val address = Input(UInt(8.W))
  val request = Input(Bool())
  val write = Input(Bool())
  val mask = Input(UInt(4.W))
  val dataWrite = Input(UInt(32.W))
  val dataRead = Output(UInt(32.W))
  /// Whether the access is to a valid register
  val valid = Output(Bool())
}

/// GBA MMIO bus
///
/// All registers are 32-bits (individual targets adjust this as needed based on mask).
/// All accesses are asynchronous / same-cycle
class MMIO(numTargets: Int) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val mem = new TargetInterface(BusAccessWidth.Word)
  })
  val targets: Seq[MmioTarget] = Seq.fill(numTargets)(IO(Flipped(new MmioTarget)))

  val queuedRequest = RegInit(false.B)
  val queuedWrite = RegInit(false.B)
  val queuedAddress = Reg(UInt(8.W))
  val queuedMask = Reg(UInt(4.W))

  val isValid = VecInit(targets.map(_.valid)).asUInt.orR
  for (target <- targets) {
    target.address := queuedAddress
    target.request := queuedAddress
    target.write := queuedWrite
    target.mask := queuedMask
    target.dataWrite := io.mem.dataWrite
  }

  when (isValid) {
    io.mem.dataRead := Mux1H(targets.map(t => (t.valid, t.dataRead)))
  } .otherwise {
    // TODO: handle open bus
    io.mem.dataRead := 0.U
  }

  io.mem.done := false.B
  when (io.enable) {
    when (queuedRequest) {
      when (queuedWrite) {
//        printf(cf"[I/O] write addr=0x${queuedAddress * 4.U}%x data=${io.mem.dataWrite}\n")
      } .otherwise {
//        printf(cf"[I/O] read  addr=0x${queuedAddress * 4.U}%x data=${io.mem.dataRead}\n")
      }

      queuedRequest := false.B
      io.mem.done := true.B
    }
    when (io.mem.request) {
      queuedRequest := true.B
      queuedWrite := io.mem.write
      // TODO: handle I/O isn't actually mirrored except for one register?
      queuedAddress := io.mem.address >> 2
      queuedMask := io.mem.mask
    }
  }
}
