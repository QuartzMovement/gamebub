package net.gamebub.framework.interface

import chisel3._
import lib.mem.PipelineMemoryInterface

class SdramV0(
    addressWidth: Int = 13,
    dataWidth: Int = 16,
    bankWidth: Int = 2,
    chips: Int = 1,

    /// Whether SDRAM controller is optimized for linear bursts
    val sdramBurst: Boolean = true
) extends Bundle {
  // TODO switch to raw signals
  val mem = Flipped(new PipelineMemoryInterface(addressWidth = 25, dataWidth = 32))
}
