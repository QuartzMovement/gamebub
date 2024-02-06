package lib.mem

import chisel3._

class MemoryInterface(addressWidth: Int, dataWidth: Int) extends Bundle {
  /** Access address */
  val address = Input(UInt(addressWidth.W))
  /** Read enable */
  val read = Input(Bool())
  /** Write enable */
  val write = Input(Bool())
  /** Read data */
  val dataRead = Output(UInt(dataWidth.W))
  /** Write data */
  val dataWrite = Input(UInt(dataWidth.W))
  /** True when the access is complete. */
  val done = Output(Bool())
}
