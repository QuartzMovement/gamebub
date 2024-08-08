package gba.cart

import chisel3._
import chisel3.util._
import lib.log.Logger
import lib.mem.MemoryInterface

object EmulatedCartridge {
  object BackupType extends ChiselEnum {
    /// No backup
    val None = Value
    /// SRAM or FRAM, 32 KiB
    val Sram = Value
    /// Flash, 64KiB or 128KiB
    val Flash = Value
    /// Eeprom, 512B or 8KiB
    val Eeprom = Value
  }

  class Config extends Bundle {
    /// Auto-detect backup size (EEPROM only)
    val backupAutodetect = Bool()
    /// Per-type backup size
    val backupSize = UInt(1.W)
    /// Backup type
    val backupType = BackupType()
    /// Whether we're using an emulated cartridge.
    val enabled = Bool()
  }
}

class EmulatedCartridge extends Module {
  val io = IO(new Bundle {
    val config = Input(new EmulatedCartridge.Config)
    val interface = Flipped(new CartridgeInterface)

    /// External ROM memory interface, assumed synchronous.
    /// Must keep read data on the bus until the next request.
    val rom = Flipped(new MemoryInterface(addressWidth = 24, dataWidth = 16))
    /// External backup (RAM) memory interface, assumed synchronous.
    /// Must keep read data on the bus until the next request.
    val backup = Flipped(new MemoryInterface(addressWidth = 17, dataWidth = 8))
    /// Whether the previous memory request has not yet completed by the time the GBA needs it to.
    val stall = Output(Bool())
  })
  val logger = Logger("cart.emu")

  io.rom.address := DontCare
  io.rom.enable := false.B
  io.rom.write := false.B
  io.rom.dataWrite := DontCare
  io.rom.writeStrobe := DontCare
  io.backup.address := DontCare
  io.backup.enable := false.B
  io.backup.write := DontCare
  io.backup.dataWrite := DontCare
  io.backup.writeStrobe := 1.U
  io.interface.IRQ := false.B
  io.interface.ADLoIn := io.rom.dataRead
  io.interface.AHiIn := DontCare
  io.stall := false.B

  val romBusy = RegInit(false.B)
  val romAddress = Reg(UInt(24.W))
  val ramStart = WireDefault(false.B)
  // Whether the cartridge controller has aborted the current request.
  // Once the data comes back, ignore it, and start the next request.
  val romAbort = RegInit(false.B)

  when (io.interface.reqStart) {
    when (romBusy) {
      logger.info(cf"Rom request aborted, new addr=0x${io.rom.address << 1}%x")
      romAbort := true.B
    } .elsewhen (io.interface.reqRom) {
      // TODO handle out-of-bounds ROM request
      logger.debug(cf"ROM request start: addr=0x${io.rom.address << 1}%x | busy=${romBusy}")
      io.rom.enable := true.B
      io.rom.address := io.interface.reqAddress
      romBusy := true.B
      romAddress := io.interface.reqAddress
    } .otherwise {
      logger.debug(cf"RAM request start: addr=0x${io.interface.reqAddress(15, 0)}%x")
      ramStart := true.B
    }
  }
  when (romBusy) {
    io.rom.enable := true.B
    io.rom.address := romAddress
    when (io.rom.done) {
      when (romAbort) {
        // Ignore this and start the new request next cycle.
        logger.debug(cf"ROM request done (ABORTED)")
      } .otherwise {
        logger.debug(cf"ROM request done: data=0x${io.rom.dataRead}%x")
      }
      romBusy := false.B
      // TODO: io.rom.enable := false.B ?
    } .elsewhen (io.interface.reqEnd) {
      logger.warn("Request stall")
      io.stall := true.B
    }
  }
  when (romAbort && !romBusy) {
    logger.debug(cf"ROM request start: addr=0x${io.rom.address << 1}%x")
    io.rom.enable := true.B
    io.rom.address := io.interface.reqAddress
    romBusy := true.B
    romAddress := io.interface.reqAddress
    romAbort := false.B
  }

  switch (io.config.backupType) {
    is (EmulatedCartridge.BackupType.None) {
      io.interface.AHiIn := 0xFF.U(8.W)
    }
    is (EmulatedCartridge.BackupType.Sram) {
      val ramAddress = Reg(UInt(16.W))
      val ramBusy = Reg(Bool())
      val ramWrite = Reg(Bool())

      io.interface.AHiIn := io.backup.dataRead
      io.backup.dataWrite := io.interface.AHiOut

      when (ramStart) {
        ramAddress := io.interface.reqAddress
        ramBusy := true.B
        ramWrite := io.interface.reqWrite

        io.backup.enable := true.B
        io.backup.address := io.interface.reqAddress
        io.backup.write := io.interface.reqWrite
      }
      when (ramBusy) {
        io.backup.enable := true.B
        io.backup.address := ramAddress
        io.backup.write := ramWrite

        when (io.backup.done) {
          ramBusy := false.B
          when (ramWrite) {
            logger.debug(cf"SRAM write done: data=0x${io.backup.dataWrite}%x")
          } .otherwise {
            logger.debug(cf"SRAM read done: data=0x${io.backup.dataRead}%x")
          }
        } .elsewhen (io.interface.reqEnd) {
          logger.warn("RAM request stall")
          io.stall := true.B
        }
      }
    }
    is (EmulatedCartridge.BackupType.Flash) {
      // Stub out flash ID
      // TODO: actually implement Flash
      val regData = Reg(UInt(8.W))
      io.interface.AHiIn := regData
      when (ramStart) {
        val stub = WireDefault(0xFF.U(8.W))
        when (io.interface.reqAddress < 2.U) {
          when (io.config.backupSize === 0.U) {
            // 64 KiB (Panasonic)
            stub := Mux(io.interface.reqAddress(0) === 0.U, 0x32.U, 0x1B.U)
          } .otherwise {
            // 128 KiB (Sanyo)
            stub := Mux(io.interface.reqAddress(0) === 0.U, 0x62.U, 0x13.U)
          }
        }
        regData := stub
        logger.debug(cf"Flash stub: ${io.interface.reqAddress}%x -> ${stub}%x")
      }
    }
    // TODO: implement EEPROM
  }
}
