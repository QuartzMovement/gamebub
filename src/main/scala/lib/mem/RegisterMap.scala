package lib.mem

import chisel3._

/** Exposes a simple map of registers as a MemoryInterface  */
object RegisterMap {
  def apply (addressWidth: Int, dataWidth: Int, entries: Seq[(Int, Data)]): MemoryInterface = {
    val byteWidth = dataWidth / 8

    // Ensure addresses are word-aligned.
    for ((addr, i) <- entries.map(_._1).zipWithIndex) {
      if (addr % byteWidth != 0) {
        throw new IllegalArgumentException(f"entry $i (at 0x$addr%x) is not aligned to $byteWidth bytes")
      }
    }
    // Ensure addresses are unique.
    entries.map(_._1).groupBy(identity).collect { case (x, List(_, _, _*)) => x }.foreach(addr => {
      throw new IllegalArgumentException(f"address 0x$addr%x is used multiple times")
    })

    val interface = Wire(new MemoryInterface(addressWidth, dataWidth))

    interface.dataRead := 0.U
    interface.done := true.B

    entries.foreach { case (address, reg) =>
      when (interface.address === address.U) {
        when (interface.read) {
          interface.dataRead := reg.asUInt
        }
        when (interface.write) {
          reg := interface.dataWrite.asTypeOf(reg)
        }
      }
    }

    interface
  }
}
