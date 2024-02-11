package lib.mem

import chisel3._

/**
 * Exposes a simple map of registers as a MemoryInterface.
 *
 * writeStrobe is ignored.
 */
object RegisterMap {
  def apply (addressWidth: Int, dataWidth: Int, entries: Seq[(Int, Data)]): MemoryInterface = {
    val byteWidth = dataWidth / 8

    // Ensure registers are not too big.
    for ((reg, i) <- entries.map(_._2).zipWithIndex) {
      if (reg.getWidth > dataWidth) {
        throw new IllegalArgumentException(f"entry $i (width ${reg.getWidth}) is larger than data width $dataWidth")
      }
    }
    // Ensure addresses are word-aligned and in bounds.
    for ((addr, i) <- entries.map(_._1).zipWithIndex) {
      if (addr % byteWidth != 0) {
        throw new IllegalArgumentException(f"entry $i (at 0x$addr%x) is not aligned to $byteWidth bytes")
      }
      if (addr >= (1 << addressWidth)) {
        throw new IllegalArgumentException(f"entry $i (at 0x$addr%x) is larger than address width $addressWidth")
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
