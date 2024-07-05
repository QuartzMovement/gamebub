package gba.cart

import chisel3._
import chisel3.util._
import lib.log.Logger
import lib.mem.MemoryInterface

class EmulatedCartridge extends Module {
  val io = IO(new Bundle {
    val interface = Flipped(new CartridgeInterface)

    /// External ROM memory interface, assumed synchronous
    val rom = Flipped(new MemoryInterface(addressWidth = 24, dataWidth = 16))
    /// Whether the previous memory request has not yet completed by the time the GBA needs it to.
    val stall = Output(Bool())
  })
  val logger = Logger("cart.emu")

  io.rom.address := DontCare
  io.rom.enable := false.B
  io.rom.write := false.B
  io.rom.dataWrite := DontCare
  io.rom.writeStrobe := DontCare
  io.interface.IRQ := false.B
  io.interface.ADLoIn := io.rom.dataRead
  io.interface.AHiIn := 0xFF.U(8.W)
  io.stall := false.B

  val romBusy = RegInit(false.B)
  val romAddress = Reg(UInt(24.W))

  when (io.interface.reqStart) {
    when (io.interface.reqRom) {
      logger.debug(cf"ROM request start: addr=0x${io.rom.address << 1}%x | busy=${romBusy}")
      io.rom.enable := true.B
      io.rom.address := io.interface.reqAddress
      romBusy := true.B
      romAddress := io.interface.reqAddress
    } .otherwise {
      logger.debug(cf"RAM request start: addr=0x${io.interface.reqAddress(15, 0)}%x")
      // TODO: implement SRAM/Flash/EEPROM
    }
  }
  when (romBusy) {
    io.rom.enable := true.B
    io.rom.address := romAddress
    when (io.rom.done) {
      logger.debug(cf"ROM request done: data=0x${io.rom.dataRead}%x")
      romBusy := false.B
    } .elsewhen (io.interface.reqEnd) {
      logger.warn("Request stall")
      io.stall := true.B
    }
  }
}
