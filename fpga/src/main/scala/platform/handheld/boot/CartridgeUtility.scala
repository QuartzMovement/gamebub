package platform.handheld.boot

import chisel3._
import chisel3.util.SRAM
import lib.mem.{MemoryInterface, RegisterMap}
import platform.handheld.HandheldCartridge

class CartridgeUtility extends Module {
  val io = IO(new Bundle {
    val registers = new MemoryInterface(addressWidth = 16, dataWidth = 32)
    val memInterface = new MemoryInterface(addressWidth = 16, dataWidth = 32)

    val cartridgeEnabled = Output(Bool())
    val cartridge = new HandheldCartridge
  })

  // 64 KiB buffer, 32 bit words with byte mask
  val mem = {
    val mem = SRAM.masked(16 * 1024, Vec(4, UInt(8.W)), numReadPorts = 0, numWritePorts = 0, numReadwritePorts = 2)
    val memHostPort = mem.readwritePorts(0)
    val memDevicePort = mem.readwritePorts(1)

    memHostPort.enable := io.memInterface.enable
    memHostPort.address := io.memInterface.address >> 2
    memHostPort.isWrite := io.memInterface.write
    memHostPort.mask.get := io.memInterface.writeStrobe.asBools
    memHostPort.writeData := io.memInterface.dataWrite.asTypeOf(memHostPort.writeData)
    io.memInterface.dataRead := memHostPort.readData.asUInt
    io.memInterface.done := RegNext(memHostPort.enable)

    memDevicePort
  }

  io.registers <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries = Seq(
    )
  )

  mem.enable := false.B
  mem.address := DontCare
  mem.isWrite := DontCare
  mem.mask.get := DontCare
  mem.writeData := DontCare

  io.cartridgeEnabled := false.B
  io.cartridge.bank0Dir := false.B
  io.cartridge.bank1Dir := false.B
  io.cartridge.bank2Dir := false.B
  io.cartridge.bank3Dir := false.B
  io.cartridge.pin30Dir := false.B
  io.cartridge.pin31Dir := false.B
  io.cartridge.bank0Out := DontCare
  io.cartridge.bank1Out := DontCare
  io.cartridge.bank2Out := DontCare
  io.cartridge.bank3Out := DontCare
  io.cartridge.pin30Out := DontCare
  io.cartridge.pin31Out := DontCare
}
