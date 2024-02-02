package platform.handheld

import chisel3._
import chisel3.util._
import gameboy.Gameboy
import gameboy.cart.{EmuCartConfig, EmuCartridge}

/**
 * Clocked by the 8.3886 MHz "Gameboy" clock.
 */
class HandheldGameboy extends Module with HandheldModule {
  val io = IO(new HandheldIo)
  def framebufferW = 160
  def framebufferH = 144

  // Gameboy
  val gameboyConfig = Gameboy.Configuration(
    skipBootrom = false,
    optimizeForSimulation = false,
    model = Gameboy.Model.Cgb,
  )
  val gameboy = Module(new Gameboy(gameboyConfig))

  // Gameboy clock control
  gameboy.io.clockConfig.enable := io.enable
  gameboy.io.clockConfig.provide8Mhz := true.B

  gameboy.io.joypad.a := io.buttons.a
  gameboy.io.joypad.b := io.buttons.b
  gameboy.io.joypad.up := io.buttons.up
  gameboy.io.joypad.down := io.buttons.down
  gameboy.io.joypad.left := io.buttons.left
  gameboy.io.joypad.right := io.buttons.right
  gameboy.io.joypad.start := io.buttons.start
  gameboy.io.joypad.select := io.buttons.select

  // Vibration unused
  io.vibrate := false.B

  // PMOD unused
  io.pmod.out := DontCare
  io.pmod.dir := 0.U(4.W)

  io.audioLeft := gameboy.io.apu.left << 6
  io.audioRight := gameboy.io.apu.right << 6

  // Link port
  io.link.soOut := gameboy.io.serial.out
  io.link.soDir := true.B
  gameboy.io.serial.in := io.link.siIn
  io.link.siOut := DontCare
  io.link.siDir := false.B
  io.link.sdOut := DontCare
  io.link.sdDir := false.B
  gameboy.io.serial.clockIn := io.link.scIn
  io.link.scOut := gameboy.io.serial.clockOut
  io.link.scDir := gameboy.io.serial.clockEnable

  // Framebuffer output
  val framebufferX = RegInit(0.U(8.W))
  val framebufferY = RegInit(0.U(8.W))
  io.framebufferX := framebufferX
  io.framebufferY := framebufferY
  io.framebufferWriteEnable := false.B
  io.framebufferDataR := DontCare
  io.framebufferDataG := DontCare
  io.framebufferDataB := DontCare

  val prevHblank = RegInit(false.B)
  val prevLcdEnable = RegInit(false.B)
  when (gameboy.io.clockConfig.enable) {
    prevHblank := gameboy.io.ppu.hblank
    prevLcdEnable := gameboy.io.ppu.lcdEnable
    when (!gameboy.io.ppu.lcdEnable) {
      // Clear the screen (to write) if LCD is disabled.
      io.framebufferWriteEnable := true.B
      io.framebufferDataR := 0x1F.U(5.W)
      io.framebufferDataG := 0x1F.U(5.W)
      io.framebufferDataB := 0x1F.U(5.W)
      when (prevLcdEnable) {
        framebufferX := 0.U
        framebufferY := 0.U
      } .elsewhen (framebufferX < 159.U) {
        framebufferX := framebufferX + 1.U
      } .elsewhen (framebufferY < 143.U) {
        framebufferX := 0.U
        framebufferY := framebufferY + 1.U
      }
    } .elsewhen (gameboy.io.ppu.vblank) {
      framebufferX := 0.U
      framebufferY := 0.U
    } .elsewhen (gameboy.io.ppu.hblank && !prevHblank) {
      framebufferX := 0.U
      framebufferY := framebufferY + 1.U
    } .elsewhen (gameboy.io.ppu.valid) {
      io.framebufferWriteEnable := true.B
      io.framebufferDataR := gameboy.io.ppu.pixel(4, 0)
      io.framebufferDataG := gameboy.io.ppu.pixel(9, 5)
      io.framebufferDataB := gameboy.io.ppu.pixel(14, 10)
      framebufferX := framebufferX + 1.U
    }
  }

  // Emulated Cartridge
  val emuCart = Module(new EmuCartridge(8 * 1024 * 1024))
  emuCart.io.config := io.temp.asTypeOf(new EmuCartConfig)
  emuCart.io.tCycle := gameboy.io.tCycle
  emuCart.io.rtcAccess.writeEnable := false.B
  emuCart.io.rtcAccess.writeState := DontCare
  emuCart.io.rtcAccess.latchSelect := DontCare

  io.sramEnable := emuCart.io.dataAccess.enable
  io.sramWrite := emuCart.io.dataAccess.write
  io.sramDataWrite := Fill(2, emuCart.io.dataAccess.dataWrite)
  emuCart.io.dataAccess.valid := true.B
  io.sramAddress := Mux(
    emuCart.io.dataAccess.selectRom,
    emuCart.io.dataAccess.address(18, 1),
    Cat(io.temp(15, 8), emuCart.io.dataAccess.address(10, 1))
  )
  emuCart.io.dataAccess.dataRead := Mux(emuCart.io.dataAccess.address(0), io.sramDataRead(15, 8), io.sramDataRead(7, 0))
  io.sramStrobe := Cat(
    emuCart.io.dataAccess.address(0),
    !emuCart.io.dataAccess.address(0),
  )

  when (emuCart.io.config.enabled) {
    io.cartridgeEnabled := false.B

    // Connect emulated cartridge
    emuCart.io.cartridgeIo <> gameboy.io.cartridge

    // Disconnect physical cartridge
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
    // Cartridge I/O
    // This t-cycle logic works with HDMA too, even though it's 2x faster,
    // because HDMA always reads, never writes.
    val cartWrite = gameboy.io.cartridge.write && (gameboy.io.tCycle === 1.U || gameboy.io.tCycle === 2.U)
    io.cartridgeEnabled := true.B

    // Bank 0: Data bus
    gameboy.io.cartridge.dataRead := io.cartridge.bank0In
    io.cartridge.bank0Out := gameboy.io.cartridge.dataWrite
    io.cartridge.bank0Dir := cartWrite // Output if writing

    // Bank 1: Address High
    io.cartridge.bank1Out := gameboy.io.cartridge.address(15, 8)
    io.cartridge.bank1Dir := true.B

    // Bank 2: Address Low
    io.cartridge.bank2Out := gameboy.io.cartridge.address(7, 0)
    io.cartridge.bank2Dir := true.B

    // Bank 3: Control signals (0: nCS, 1: nRD, 2: nWR, 3: PHI)
    io.cartridge.bank3Dir := true.B
    io.cartridge.bank3Out := Cat(
      0.U(1.W), // PHI
      ~cartWrite, // nWR
      cartWrite, // nRD
      gameboy.io.cartridge.chipSelect, // nCS
    )

    // Pin 30: nRST
    io.cartridge.pin30Dir := true.B
    io.cartridge.pin30Out := true.B

    // Pin 31: VIN
    io.cartridge.pin31Dir := false.B
    io.cartridge.pin31Out := DontCare

    // Disconnect emulated cartridge
    emuCart.io.cartridgeIo.write := false.B
    emuCart.io.cartridgeIo.enable := false.B
    emuCart.io.cartridgeIo.deadline := false.B
    emuCart.io.cartridgeIo.dataWrite := 0.U
    emuCart.io.cartridgeIo.chipSelect := false.B
    emuCart.io.cartridgeIo.address := 0.U
  }
}