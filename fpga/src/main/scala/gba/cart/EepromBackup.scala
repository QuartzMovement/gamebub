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
  val readPulse = !io.nRD && RegNext(io.nRD)
  val writePulse = !io.nWR && RegNext(io.nWR)

  // Size autodetection, based on number of bits transferred in the first read
  val eepromSize = WireDefault(io.configSize)
  val regDetectCounter = RegInit(0.U(5.W))
  val regDetectDone = RegInit(false.B)
  val regDetectSize = Reg(Bool())
  when (io.configAutodetect && !regDetectDone) {
    when (io.selected && writePulse) {
      regDetectCounter := regDetectCounter + 1.U
    }
    when (regDetectCounter > 0.U && !io.selected) {
      // 512B: 9 bits
      // 8KB: 17 bits
      regDetectDone := true.B
      val detectedSize = regDetectCounter > 9.U
      regDetectSize := detectedSize
      logger.warn(cf"Detected EEPROM size: ${detectedSize} (counter: ${regDetectCounter})")
    }
  }
  when (io.configAutodetect && regDetectDone) {
    eepromSize := regDetectSize
  }

  when (io.selected) {
    when (readPulse) {
      logger.debug(cf"EEPROM read")
    }
    when (writePulse) {
      logger.debug(cf"EEPROM write: ${io.dataWrite}")
    }
  }
}
