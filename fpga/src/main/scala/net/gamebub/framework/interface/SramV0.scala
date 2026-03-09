package net.gamebub.framework.interface

import chisel3._
import lib.mem.MemoryInterface

class SramV0(
    addressWidth: Int = 18,
    dataWidth: Int = 16,
) extends Bundle {
  // TODO switch to raw signals
  val mem = Flipped(new MemoryInterface(addressWidth = 18, dataWidth = 16))
}
