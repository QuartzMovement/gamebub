package lib.mem

import chisel3._

class MemoryInterface(addressWidth: Int, dataWidth: Int) extends Bundle {
  /** Access address */
  val address = Output(UInt(addressWidth.W))
  /** Read enable */
  val read = Output(Bool())
  /** Write enable */
  val write = Output(Bool())
  /** Read data */
  val dataRead = Input(UInt(dataWidth.W))
  /** Write data */
  val dataWrite = Output(UInt(dataWidth.W))
  /** True when the access is complete. */
  val done = Input(Bool())
}
