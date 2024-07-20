package platform.handheld

import chisel3._
import chisel3.util._
import gba.GBA
import gba.cart.EmulatedCartridge
import lib.mem.{MemoryInterface, MemoryMap, RegisterMap}

/**
 * Clocked by a 16777216 Hz clock.
 */
class HandheldGba extends Module with HandheldModule {
  val io = IO(new HandheldIo)
  def framebufferW = 240
  def framebufferH = 160

  val configRegEmuCart = RegInit(0.U.asTypeOf(new EmulatedCartridge.Config))
  // TODO support emu cart mask
  val statRegStalls = RegInit(0.U(32.W))
  val statRegCycles = RegInit(0.U(32.W))

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
        0x0000 -> RegisterMap.Entry.rw(configRegEmuCart),

        0x1000 -> RegisterMap.Entry.rw(statRegStalls),
        0x1004 -> RegisterMap.Entry.rw(statRegCycles),
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
  when (io.reset) {
    gba.reset := true.B
  }
  val cartStall = WireDefault(false.B)
  gba.io.enable := false.B
  when (io.enable) {
    when (cartStall) {
      statRegStalls := statRegStalls + 1.U
    }.otherwise {
      gba.io.enable := true.B
      statRegCycles := statRegCycles + 1.U
    }
  }

  // Emulated cartridge
  val emuCart = Module(new EmulatedCartridge)
  when (io.reset) {
    emuCart.reset := true.B
  }
  emuCart.io.config := configRegEmuCart

  // Convert 16-bit addresses to 32-bit byte addresses
  // Also dealing with enable = true when done = true
  // Note however that EmulatedCartridge will never do that, because
  // reqEnd goes high, then the next cycle reqStart can go high again
  val emuCartBusy = RegInit(false.B)
  val emuCartAddr = Reg(UInt(24.W))
  val emuCartData = Reg(UInt(16.W))
  emuCart.io.rom.done := io.sdram.done
  emuCart.io.rom.dataRead := emuCartData
  when (emuCartBusy) {
    io.sdram.enable := true.B
    io.sdram.address := Cat(emuCartAddr(23, 1), 0.U(2.W))
    when (io.sdram.done) {
      emuCartBusy := false.B
      val data = io.sdram.dataRead.asTypeOf(Vec(2, UInt(16.W)))(emuCartAddr(0))
      emuCartData := data
      emuCart.io.rom.dataRead := data
    }
  } .elsewhen (emuCart.io.rom.enable) {
    // TODO: fix combinational loop here when this is uncommented
//    io.sdram.enable := true.B
    io.sdram.address := Cat(emuCart.io.rom.address(23, 1), 0.U(2.W))
    emuCartAddr := emuCart.io.rom.address
    emuCartBusy := true.B
  }

  // TODO: actually connect to SRAM
  emuCart.io.backup.dataRead := 0xFF.U(8.W)
  emuCart.io.backup.done := true.B

  // Cartridge
  when (configRegEmuCart.enabled) {
    // Connect emulated cartridge
    gba.io.cartridge <> emuCart.io.interface
    cartStall := emuCart.io.stall

    // Disconnect physical cartridge
    io.cartridgeEnabled := false.B
    io.cartridge.bank0Out := DontCare
    io.cartridge.bank1Out := DontCare
    io.cartridge.bank2Out := DontCare
    io.cartridge.bank3Out := DontCare
    io.cartridge.pin30Out := DontCare
    io.cartridge.pin31Out := DontCare
    io.cartridge.bank0Dir := false.B
    io.cartridge.bank1Dir := false.B
    io.cartridge.bank2Dir := false.B
    io.cartridge.bank3Dir := false.B
    io.cartridge.pin30Dir := false.B
    io.cartridge.pin31Dir := false.B
  } .otherwise {
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

    // Disconnected emulated cartridge
    emuCart.io.interface.phi := false.B
    emuCart.io.interface.nWR := true.B
    emuCart.io.interface.nRD := true.B
    emuCart.io.interface.nCS := true.B
    emuCart.io.interface.ADLoOut := DontCare
    emuCart.io.interface.ADLoDir := DontCare
    emuCart.io.interface.AHiOut := DontCare
    emuCart.io.interface.AHiDir := DontCare
    emuCart.io.interface.nCS2 := true.B
    emuCart.io.interface.reqStart := false.B
    emuCart.io.interface.reqRom := DontCare
    emuCart.io.interface.reqWrite := DontCare
    emuCart.io.interface.reqAddress := DontCare
    emuCart.io.interface.reqEnd := false.B
  }

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

  io.pmod.out := Cat(clock.asBool, gba.io.cartridge.nWR, gba.io.cartridge.nRD, gba.io.cartridge.nCS)
  io.pmod.dir := "b1111".U(4.W)

  io.link.soOut := DontCare
  io.link.soDir := false.B
  io.link.siOut := DontCare
  io.link.siDir := false.B
  io.link.sdOut := DontCare
  io.link.sdDir := false.B
  io.link.scOut := DontCare
  io.link.scDir := false.B
}