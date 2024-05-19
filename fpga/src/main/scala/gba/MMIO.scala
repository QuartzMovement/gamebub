package gba

import chisel3._
import chisel3.util._
import gba.mem.TargetInterface

class MmioTarget extends Bundle {
  /// *Word* address
  val address = Input(UInt(9.W))
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
    val mem = new TargetInterface(32.W)
  })
  val targets: Seq[MmioTarget] = Seq.fill(numTargets)(IO(Flipped(new MmioTarget)))

  val queuedRequest = RegInit(false.B)
  val queuedWrite = RegInit(false.B)
  val queuedAddress = Reg(UInt(8.W))
  val queuedMask = Reg(UInt(4.W))

  val isValid = VecInit(targets.map(_.valid)).asUInt.orR
  for (target <- targets) {
    target.address := queuedAddress
    target.request := queuedRequest
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

object MmioMap {
  def apply (entries: (Int, Entry)*): MmioTarget = {
    // Ensure addresses are word-aligned and in bounds.
    for ((addr, i) <- entries.map(_._1).zipWithIndex) {
      if (addr % 4 != 0) {
        throw new IllegalArgumentException(f"entry $i (at 0x$addr%x) is not aligned")
      }
      if (addr >= (1 << 11)) {
        throw new IllegalArgumentException(f"entry $i (at 0x$addr%x) is larger than address width")
      }
    }

    val interface = Wire(new MmioTarget)
    interface.dataRead := DontCare
    interface.valid := false.B

    entries.foreach { case (address, reg) =>
      when (interface.request && interface.address === (address / 4).U) {
        val (readData, readValid) = reg.read.fn(!interface.write)
        reg.write.fn(interface.write, interface.dataWrite, interface.mask)
        interface.valid := readValid

        when (!interface.write) {
          interface.dataRead := readData
        }
      }
    }

    interface
  }

  // (enable) => (data, valid)
  case class ReadFn(fn: Bool => (UInt, Bool))
  object ReadFn {
    // Simple read from a register or wire.
    def apply(reg: Data): ReadFn = ReadFn(_ => (reg.asUInt, true.B))

    // No-op read.
    def apply(): ReadFn = ReadFn(_ => (0.U, false.B))
  }

  // (enable, data, mask) => ()
  case class WriteFn(fn: (Bool, UInt, UInt) => Unit)
  object WriteFn {
    // Simple write to a register.
    def apply(reg: Data): WriteFn = WriteFn((enable, data, mask) => {
      when (enable) {
        val newDataVec = VecInit((0 until 4).map(i => data(i * 8 + 7, i * 8)))
        val oldData = reg.asUInt.pad(32)
        val oldDataVec = VecInit((0 until 4).map(i => oldData(i * 8 + 7, i * 8)))
        val combined = VecInit((0 until 4).map(i => Mux(mask(i), newDataVec(i), oldDataVec(i))))
        reg := combined.asTypeOf(reg)
      }
    })

    // No-op write.
    def apply(): WriteFn = WriteFn((_, _, _) => ())
  }

  case class Entry(read: ReadFn, write: WriteFn)
  object Entry {
    def r(reg: Data): Entry = Entry(ReadFn(reg), WriteFn())

    def w(reg: Data): Entry = Entry(ReadFn(), WriteFn(reg))

    def rw(reg: Data): Entry = Entry(ReadFn(reg), WriteFn(reg))
  }
}
