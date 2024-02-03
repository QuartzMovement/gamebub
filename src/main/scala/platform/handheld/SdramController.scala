package platform.handheld

import chisel3._
import chisel3.util._

case class SdramConfig(
  addressWidth: Int = 13,
  dataWidth: Int = 16,
  bankWidth: Int = 2,
  rowWidth: Int = 13,
  columnWidth: Int = 9,
) {
  /** The data bus width (in bytes). */
  val dataWidthBytes: Int = dataWidth / 8
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
  })

  io.signals := DontCare
}
