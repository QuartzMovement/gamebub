package platform.handheld

import chisel3._
import chisel3.util._
import gameboy.Gameboy
import gameboy.cart.{EmuCartConfig, EmuCartridge, Mbc3RtcAccess, RtcState}
import lib.mem.RegisterMap

/**
 * Clocked by the 8.3886 MHz "Gameboy" clock.
 */
class HandheldGameboy extends Module with HandheldModule {
  val io = IO(new HandheldIo)
  def framebufferW = 160
  def framebufferH = 144

  // Config
  val configRegEmuCart = RegInit(0.U.asTypeOf(new EmuCartConfig))
  val configRegRomAddress = RegInit(0.U(19.W))
  val configRegRomMask = RegInit(0.U(23.W))
  val configRegRamAddress = RegInit(0.U(19.W))
  val configRegRamMask = RegInit(0.U(17.W))
  val configRegImuAccelX = RegInit(0.U(16.W))
  val configRegImuAccelY = RegInit(0.U(16.W))
  val statRegStalls = RegInit(0.U(32.W))
  val statRegCycles = RegInit(0.U(32.W))

  val emuCartRtcAccess = Wire(new Mbc3RtcAccess)
  emuCartRtcAccess.writeEnable := false.B
  emuCartRtcAccess.writeState := DontCare
  emuCartRtcAccess.latchSelect := DontCare
  private def makeRtcAccess(latched: Boolean): RegisterMap.Entry = {
    RegisterMap.Entry(
      (new RtcState).getWidth,
      read = RegisterMap.ReadFn((read: Bool) => {
        when (read) { emuCartRtcAccess.latchSelect := latched.B }
        emuCartRtcAccess.readState.asUInt
      }),
      write = RegisterMap.WriteFn((write: Bool, data: UInt) =>
        when (write) {
          emuCartRtcAccess.latchSelect := latched.B
          emuCartRtcAccess.writeState := data.asTypeOf(new RtcState)
          emuCartRtcAccess.writeEnable := true.B
        }
      ),
    )
  }

  suppressEnumCastWarning {
    io.mcuInterface <> RegisterMap(
      addressWidth = 16,
      dataWidth = 32,
      entries = Seq(
        0x0000 -> RegisterMap.Entry.rw(configRegEmuCart), // Suppressing mbcType enum cast
        0x0004 -> RegisterMap.Entry.rw(configRegRomAddress),
        0x0008 -> RegisterMap.Entry.rw(configRegRomMask),
        0x000C -> RegisterMap.Entry.rw(configRegRamAddress),
        0x0010 -> RegisterMap.Entry.rw(configRegRamMask),
        0x0014 -> makeRtcAccess(latched = false),
        0x0018 -> makeRtcAccess(latched = true),
        0x001C -> RegisterMap.Entry.rw(configRegImuAccelX),
        0x0020 -> RegisterMap.Entry.rw(configRegImuAccelY),

        0x1000 -> RegisterMap.Entry.rw(statRegStalls),
        0x1004 -> RegisterMap.Entry.rw(statRegCycles),
      )
    )
  }

  // Gameboy
  val gameboyConfig = Gameboy.Configuration(
    skipBootrom = false,
    optimizeForSimulation = false,
    model = Gameboy.Model.Cgb,
  )
  val gameboy = Module(new Gameboy(gameboyConfig))
  when (io.reset) {
    gameboy.reset := true.B
  }

  // Gameboy clock control
  val waitingForCart = Wire(Bool())
  val cartStall = gameboy.io.cartridge.deadline && waitingForCart
  gameboy.io.clockConfig.enable := false.B
  when (io.enable) {
    when (cartStall) {
      statRegStalls := statRegStalls + 1.U
    }.otherwise {
      gameboy.io.clockConfig.enable := true.B
      statRegCycles := statRegCycles + 1.U
    }
  }
  gameboy.io.clockConfig.provide8Mhz := true.B

  gameboy.io.joypad.a := io.buttons.a
  gameboy.io.joypad.b := io.buttons.b
  gameboy.io.joypad.up := io.buttons.up
  gameboy.io.joypad.down := io.buttons.down
  gameboy.io.joypad.left := io.buttons.left
  gameboy.io.joypad.right := io.buttons.right
  gameboy.io.joypad.start := io.buttons.start
  gameboy.io.joypad.select := io.buttons.select

  // Vibration unused by default.
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
  io.framebufferData.a := DontCare
  io.framebufferData.r := DontCare
  io.framebufferData.g := DontCare
  io.framebufferData.b := DontCare
  io.vblank := gameboy.io.ppu.vblank

  val prevHblank = RegInit(false.B)
  val prevLcdEnable = RegInit(false.B)
  when (gameboy.io.clockConfig.enable) {
    prevHblank := gameboy.io.ppu.hblank
    prevLcdEnable := gameboy.io.ppu.lcdEnable
    when (!gameboy.io.ppu.lcdEnable) {
      // Clear the screen (to write) if LCD is disabled.
      io.framebufferWriteEnable := true.B
      io.framebufferData.r := 0x1F.U(5.W)
      io.framebufferData.g := 0x1F.U(5.W)
      io.framebufferData.b := 0x1F.U(5.W)
      when (prevLcdEnable) {
        framebufferX := 0.U
        framebufferY := 0.U
      } .elsewhen (framebufferX < 159.U) {
        framebufferX := framebufferX + 1.U
      } .elsewhen (framebufferY < 143.U) {
        framebufferX := 0.U
        framebufferY := framebufferY + 1.U
      } .otherwise {
        io.vblank := true.B
      }
    } .elsewhen (gameboy.io.ppu.vblank) {
      framebufferX := 0.U
      framebufferY := 0.U
    } .elsewhen (gameboy.io.ppu.hblank && !prevHblank) {
      framebufferX := 0.U
      framebufferY := framebufferY + 1.U
    } .elsewhen (gameboy.io.ppu.valid) {
      io.framebufferWriteEnable := true.B
      io.framebufferData.r := gameboy.io.ppu.pixel(4, 0)
      io.framebufferData.g := gameboy.io.ppu.pixel(9, 5)
      io.framebufferData.b := gameboy.io.ppu.pixel(14, 10)
      framebufferX := framebufferX + 1.U
    }
  }

  // Emulated Cartridge
  val emuCart = Module(new EmuCartridge(8 * 1024 * 1024))
  when (io.reset) {
    emuCart.reset := true.B
  }
  emuCart.io.config := configRegEmuCart
  emuCart.io.tCycle := gameboy.io.tCycle
  emuCart.io.rtcAccess <> emuCartRtcAccess
  emuCart.io.imu.x := configRegImuAccelX
  emuCart.io.imu.y := configRegImuAccelY

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

  val emuCartStartAccess = emuCart.io.dataAccess.enable && !RegNext(emuCart.io.dataAccess.enable)
  val emuCartBusy = RegInit(false.B)
  val emuCartDataRead = Reg(UInt(8.W))

  when (emuCartStartAccess) {
    emuCartBusy := true.B
  }
  emuCart.io.dataAccess.valid := false.B
  emuCart.io.dataAccess.dataRead := emuCartDataRead
  when (emuCartStartAccess || emuCartBusy) {
    when (emuCart.io.dataAccess.selectRom) {
      when (emuCart.io.dataAccess.write) {
        // Don't handle ROM writes.
        emuCart.io.dataAccess.valid := true.B
      } .otherwise {
        io.sdram.enable := true.B
        io.sdram.write := false.B
        io.sdram.address := configRegRomAddress + (Cat(emuCart.io.dataAccess.address(22, 2), "b00".U(2.W)) & configRegRomMask)
        emuCart.io.dataAccess.dataRead := io.sdram.dataRead
          .asTypeOf(Vec(4, UInt(8.W)))(
            emuCart.io.dataAccess.address(1, 0)
          )
        emuCart.io.dataAccess.valid := io.sdram.done
      }
    } .otherwise {
      io.sram.enable := true.B
      io.sram.write := emuCart.io.dataAccess.write
      io.sram.address := configRegRamAddress + (Cat(emuCart.io.dataAccess.address(16, 1), "b0".U(1.W)) & configRegRamMask)
      io.sram.dataWrite := Fill(2, emuCart.io.dataAccess.dataWrite)
      io.sram.writeStrobe := Mux(emuCart.io.dataAccess.address(0), "b10".U(2.W), "b01".U(2.W))
      emuCart.io.dataAccess.valid := io.sram.done
      emuCart.io.dataAccess.dataRead := Mux(
        emuCart.io.dataAccess.address(0),
        io.sram.dataRead(15, 8),
        io.sram.dataRead(7, 0)
      )
    }
    when (emuCart.io.dataAccess.valid) {
      emuCartBusy := false.B
      emuCartDataRead := emuCart.io.dataAccess.dataRead
    }
  }

  when (emuCart.io.config.enabled) {
    io.cartridgeEnabled := false.B

    // Connect emulated cartridge
    emuCart.io.cartridgeIo <> gameboy.io.cartridge
    waitingForCart := emuCart.io.waitingForAccess
    io.vibrate := emuCart.io.rumble

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
    waitingForCart := false.B

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