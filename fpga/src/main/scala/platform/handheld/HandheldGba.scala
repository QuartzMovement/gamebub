package platform.handheld

import chisel3._
import chisel3.util._
import gameboy.Gameboy
import gameboy.cart.{EmuCartConfig, EmuCartridge, Mbc3RtcAccess, RtcState}
import gba.GBA
import lib.mem.{MemoryInterface, MemoryMap, RegisterMap}

/**
 * Clocked by a 16777216 Hz clock.
 */
class HandheldGba extends Module with HandheldModule {
  val io = IO(new HandheldIo)
  def framebufferW = 240
  def framebufferH = 160

  // TODO support emu cartridge

  val registerInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val biosInterface = Wire(new MemoryInterface(addressWidth = 14, dataWidth = 32)) // 16 KiB
  io.mcuInterface <> MemoryMap(
    addressWidth = 24,
    dataWidth = 32,
    entries = Seq(
      "b0000".U(4.W) -> registerInterface,
      "b0001".U(4.W) -> biosInterface,
    ))

  suppressEnumCastWarning {
    registerInterface <> RegisterMap(
      addressWidth = 16,
      dataWidth = 32,
      entries = Seq(
      )
    )
  }

  // Memory interfaces
  io.sdram.enable := false.B
  io.sdram.write := false.B
  io.sdram.address := DontCare
  io.sdram.dataWrite := DontCare
  io.sdram.writeStrobe := DontCare
  io.sram.enable := false.B
  io.sram.write := false.B
  io.sram.address := DontCare
  io.sram.dataWrite := DontCare
  io.sram.writeStrobe := DontCare

  // Gameboy
  val gba = Module(new GBA)
  gba.io.enable := io.enable
  when (io.reset) {
    gba.reset := true.B
  }

  // Cartridge
  io.cartridgeEnabled := true.B
  io.cartridge.bank0Dir := gba.io.cartridge.AHiDir
  io.cartridge.bank0Out := gba.io.cartridge.AHiOut
  gba.io.cartridge.AHiIn := io.cartridge.bank0In
  io.cartridge.bank1Dir := gba.io.cartridge.ADLoDir
  io.cartridge.bank1Out := gba.io.cartridge.ADLoOut(15, 8)
  io.cartridge.bank2Dir := gba.io.cartridge.ADLoDir
  io.cartridge.bank2Out := gba.io.cartridge.ADLoOut(7, 0)
  gba.io.cartridge.ADLoIn := Cat(io.cartridge.bank1In, io.cartridge.bank2In)

  io.cartridge.bank3Dir := true.B
  io.cartridge.bank3Out := Cat(
    gba.io.cartridge.phi,
    gba.io.cartridge.nWR,
    gba.io.cartridge.nRD,
    gba.io.cartridge.nCS,
  )
  io.cartridge.pin30Dir := true.B
  io.cartridge.pin30Out := gba.io.cartridge.nCS2
  io.cartridge.pin31Dir := false.B
  io.cartridge.pin31Out := DontCare
  gba.io.cartridge.IRQ := io.cartridge.pin31In

  // Video output
  val framebufferX = RegInit(0.U(8.W))
  val framebufferY = RegInit(0.U(8.W))
  io.framebufferX := framebufferX
  io.framebufferY := framebufferY
  io.framebufferWriteEnable := false.B
  io.framebufferData.a := DontCare
  io.framebufferData.r := DontCare
  io.framebufferData.g := DontCare
  io.framebufferData.b := DontCare
  io.vblank := gba.io.ppu.vblank

  val prevHblank = RegInit(false.B)
  when (gba.io.enable) {
    prevHblank := gba.io.ppu.hblank
    when (gba.io.ppu.vblank) {
      framebufferX := 0.U
      framebufferY := 0.U
    } .elsewhen (gba.io.ppu.hblank && !prevHblank) {
      framebufferX := 0.U
      framebufferY := framebufferY + 1.U
    } .elsewhen (gba.io.ppu.valid) {
      io.framebufferWriteEnable := true.B
      io.framebufferData.r := gba.io.ppu.pixel(4, 0)
      io.framebufferData.g := gba.io.ppu.pixel(9, 5)
      io.framebufferData.b := gba.io.ppu.pixel(14, 10)
      framebufferX := framebufferX + 1.U
    }
  }

  // Audio output
  io.audioLeft := gba.io.apu.left << 6
  io.audioRight := gba.io.apu.right << 6

  // Keypad
  gba.io.keypad.a := io.buttons.a
  gba.io.keypad.b := io.buttons.b
  gba.io.keypad.l := io.buttons.l
  gba.io.keypad.r := io.buttons.r
  gba.io.keypad.up := io.buttons.up
  gba.io.keypad.down := io.buttons.down
  gba.io.keypad.left := io.buttons.left
  gba.io.keypad.right := io.buttons.right
  gba.io.keypad.start := io.buttons.start
  gba.io.keypad.select := io.buttons.select

  // BIOS
  val bios = SRAM(16 * 1024 / 4, UInt(32.W), numReadPorts = 1, numWritePorts = 1, numReadwritePorts = 0)
  bios.writePorts(0).enable := biosInterface.enable && biosInterface.write
  bios.writePorts(0).address := biosInterface.address >> 2
  bios.writePorts(0).data := biosInterface.dataWrite
  biosInterface.dataRead := 0.U
  biosInterface.done := RegNext(bios.writePorts(0).enable || bios.readPorts(0).enable)
  bios.readPorts(0).enable := gba.io.biosRom.read
  bios.readPorts(0).address := gba.io.biosRom.address
  gba.io.biosRom.data := bios.readPorts(0).data

  // EWRAM
  // TODO support part of this going to EmuCartridge
  io.sram <> gba.io.ewram
  io.sram.address := gba.io.ewram.address << 1 // io.sram is byte-based, gba.io.ewram is word based


  // Unused
  io.vibrate := false.B

  io.pmod.out := DontCare
  io.pmod.dir := 0.U(4.W)

  io.link.soOut := DontCare
  io.link.soDir := false.B
  io.link.siOut := DontCare
  io.link.siDir := false.B
  io.link.sdOut := DontCare
  io.link.sdDir := false.B
  io.link.scOut := DontCare
  io.link.scDir := false.B
}