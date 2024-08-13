package gba.cart

import chisel3._
import lib.log.Logger
import lib.mem.MemoryInterface

class EepromBackup extends Module {
  val io = IO(new Bundle {
    val configSize = Input(UInt(1.W))
    val configAutodetect = Input(Bool())

    /// Whether the EEPROM chip is selected
    val selected = Input(Bool())
    /// State of nRD pin
    val nRD = Input(Bool())
    /// State of nWR pin
    val nWR = Input(Bool())
    val dataRead = Output(UInt(1.W))
    val dataWrite = Input(UInt(1.W))
    val reqEnd = Input(Bool())

    val stall = Output(Bool())

    val backup = Flipped(new MemoryInterface(addressWidth = 13, dataWidth = 8))
  })
  val logger = Logger("cart.emu.eeprom")

  io.stall := false.B
  io.backup.enable := false.B
  io.backup.address := DontCare
  io.backup.dataWrite := DontCare
  io.backup.write := DontCare
  io.backup.writeStrobe := 1.U
  io.backup.dataRead := DontCare
  io.dataRead := 1.U


  when (io.selected) {
    when (!io.nRD && RegNext(io.nRD)) {
      logger.debug(cf"EEPROM read")
    }
    when (!io.nWR && RegNext(io.nWR)) {
      logger.debug(cf"EEPROM write: ${io.dataWrite}")
    }
  }
}
