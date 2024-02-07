package platform.handheld

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface

case class SdramConfig(
  /** Physical address width */
  addressWidth: Int = 13,
  /** Physical data width */
  dataWidth: Int = 16,
  /** Bank address width */
  bankWidth: Int = 2,
  /** Row address width */
  rowWidth: Int = 13,
  /** Column address width */
  columnWidth: Int = 9,

  /** The burst size in words. */
  burstSize: Int = 2,
) {
  /** The physical data bus width (in bytes). */
  val dataWidthBytes: Int = dataWidth / 8

  /** The logical address width. */
  val logicalAddressWidth: Int = bankWidth + rowWidth + columnWidth + log2Ceil(dataWidthBytes)

  /** The logical data width, considering burst size. */
  val logicalDataWidth: Int = dataWidth * burstSize
}

class SdramSignals(config: SdramConfig) extends Bundle {
  // clk is not included.

  /** Clock Enable */
  val cke = Output(Bool())
  /** Chip Select (active-low) */
  val cs = Output(Bool())
  /** Row Address Strobe (active-low) */
  val ras = Output(Bool())
  /** Column Address Strobe (active-low) */
  val cas = Output(Bool())
  /** Write Enable (active-low) */
  val we = Output(Bool())
  /** Data Mask (byte) */
  val dqm = Output(UInt(config.dataWidthBytes.W))
  /** Bank Select */
  val bank = Output(UInt(config.bankWidth.W))
  /** Address */
  val address = Output(UInt(config.addressWidth.W))
  /** Data Input */
  val dataIn = Input(UInt(config.dataWidth.W))
  /** Data Output */
  val dataOut = Output(UInt(config.dataWidth.W))
  /** Data Direction: true for output. */
  val dataDir = Output(Bool())
}

class SdramController(config: SdramConfig) extends Module {
  val io = IO(new Bundle {
    val signals = new SdramSignals(config)

    val mem = new MemoryInterface(addressWidth = config.logicalAddressWidth, dataWidth = config.logicalDataWidth)
  })

  io.signals := DontCare

  io.mem.done := true.B
  io.mem.dataRead := 0.U
}
