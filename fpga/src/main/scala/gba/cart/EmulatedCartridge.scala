package gba.cart

import chisel3._
import chisel3.util._
import lib.log.Logger

class EmulatedCartridgeDataAccess extends Bundle {
  val romAccess = Output(Bool())
  val romAddress = Output(UInt(24.W))
  val romDataRead = Input(UInt(16.W))
  val romDone = Input(Bool())
}

class EmulatedCartridge extends Module {
  val io = IO(new Bundle {
    val interface = Flipped(new CartridgeInterface)

    val data = new EmulatedCartridgeDataAccess

    // Whether the controller is about to consume data that isn't yet ready.
    val shouldStall = Output(Bool())
  })
  val logger = Logger("cart.emu")

  io.data.romAccess := false.B
  io.data.romAddress := DontCare
  io.interface.nIRQ := true.B
  io.interface.ADLoIn := io.data.romDataRead
  io.interface.AHiIn := DontCare
  io.shouldStall := false.B

  val romBusy = RegInit(false.B)

  when (io.interface.reqStart && io.interface.reqRom) {
    logger.debug(cf"ROM request start: addr=0x${io.data.romAddress << 1}%x | busy=${romBusy}")
    io.data.romAccess := true.B
    io.data.romAddress := io.interface.reqAddress
    romBusy := true.B
  }
  when (romBusy) {
    when (io.data.romDone) {
      logger.debug(cf"ROM request done: data=0x${io.data.romDataRead}%x")
      romBusy := false.B
    } .elsewhen (io.interface.reqEnd) {
      logger.warn("Request stall")
      io.shouldStall := true.B
    }
  }
}
