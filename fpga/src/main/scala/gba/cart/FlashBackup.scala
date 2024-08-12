package gba.cart

import chisel3._
import lib.log.Logger
import lib.mem.MemoryInterface

class FlashBackup extends Module {
  val io = IO(new Bundle {
    val ramEnable = Input(Bool())
    val ramAddress = Input(UInt(32.W))
    val ramIsWrite = Input(Bool())
    val ramDataRead = Output(UInt(8.W))
    val ramDataWrite = Input(UInt(8.W))
    val ramReqEnd = Input(Bool())

    val stall = Output(Bool())

    val backup = Flipped(new MemoryInterface(addressWidth = 17, dataWidth = 8))
    val size = Input(UInt(1.W))
  })
  val logger = Logger("cart.emu.flash")

  io.stall := false.B

  io.backup.enable := false.B
  io.backup.address := DontCare
  io.backup.write := DontCare
  io.backup.writeStrobe := 1.U
  io.backup.dataWrite := io.ramDataWrite

  // Stub out flash ID
  val regData = Reg(UInt(8.W))
  io.ramDataRead := regData
  when (io.ramEnable) {
    val stub = WireDefault(0xFF.U(8.W))
    when (io.ramAddress < 2.U) {
      when (io.size === 0.U) {
        // 64 KiB (Panasonic)
        stub := Mux(io.ramAddress(0) === 0.U, 0x32.U, 0x1B.U)
      } .otherwise {
        // 128 KiB (Sanyo)
        stub := Mux(io.ramAddress(0) === 0.U, 0x62.U, 0x13.U)
      }
    }
    regData := stub
  }
}
