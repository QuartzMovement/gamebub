package xilinx

import chisel3._

class xpm_cdc_handshake(
  width: Int,
  destExtHsk: Boolean,
  destSyncFf: Int = 2,
  srcSyncFf: Int = 2,
  initSyncFf: Boolean = true,
  simAssertChk: Boolean = true,
) extends BlackBox(Map(
  "DEST_EXT_HSK" -> (if (destExtHsk) 1 else 0),
  "DEST_SYNC_FF" -> destSyncFf,
  "INIT_SYNC_FF" -> (if (initSyncFf) 1 else 0),
  "SIM_ASSERT_CHK" -> (if (simAssertChk) 1 else 0),
  "SRC_SYNC_FF" -> srcSyncFf,
  "WIDTH" -> width,
)) {
  val io = IO(new Bundle {
    val dest_ack = Input(Bool())
    val dest_clk = Input(Clock())
    val dest_out = Output(UInt(width.W))
    val dest_req = Output(Bool())
    val src_clk = Input(Clock())
    val src_in = Input(UInt(width.W))
    val src_rcv = Output(Bool())
    val src_send = Input(Bool())
  })
}
