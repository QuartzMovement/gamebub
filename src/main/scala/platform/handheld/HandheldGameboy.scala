package platform.handheld

import chisel3._
import chisel3.util._
import gameboy.apu.ApuOutput
import gameboy.ppu.PpuOutput
import gameboy.{CartridgeIo, Gameboy, JoypadState, SerialIo}

object HandheldGameboy extends App {
  emitVerilog(new HandheldGameboy, args)
}

/**
 * Clocked by the 8.3886 MHz "Gameboy" clock.
 */
class HandheldGameboy extends Module {
  val io = IO(new Bundle {
    // Gameboy output
    val cartridge = new CartridgeIo()
    val joypad = Input(new JoypadState)
    val apu = new ApuOutput
    val serial = new SerialIo()
    val tCycle = Output(UInt(2.W))

    // Framebuffer output
    val framebufferWriteAddr = Output(UInt(15.W))
    val framebufferWriteEnable = Output(Bool())
    val framebufferWriteData = Output(UInt(15.W))
  })

  // Gameboy
  val gameboyConfig = Gameboy.Configuration(
    skipBootrom = false,
    optimizeForSimulation = false,
    model = Gameboy.Model.Cgb,
  )
  val gameboy = Module(new Gameboy(gameboyConfig))
  io.joypad <> gameboy.io.joypad
  io.apu <> gameboy.io.apu
  io.serial <> gameboy.io.serial
  io.tCycle <> gameboy.io.tCycle

  io.framebufferWriteAddr := DontCare
  io.framebufferWriteData := DontCare
  io.framebufferWriteEnable := false.B

  // Gameboy clock control
  gameboy.io.clockConfig.enable := true.B
  gameboy.io.clockConfig.provide8Mhz := true.B

  // Framebuffer output
  val framebufferX = RegInit(0.U(8.W))
  val framebufferY = RegInit(0.U(8.W))
  val framebufferIndex = (framebufferY * 160.U(8.W)) + framebufferX

  val prevHblank = RegInit(false.B)
  val prevLcdEnable = RegInit(false.B)
  when (gameboy.io.clockConfig.enable) {
    prevHblank := gameboy.io.ppu.hblank
    prevLcdEnable := gameboy.io.ppu.lcdEnable
    when (!gameboy.io.ppu.lcdEnable) {
      // Clear the screen if LCD is disabled.
      io.framebufferWriteEnable := true.B
      io.framebufferWriteAddr := framebufferIndex
      io.framebufferWriteData := 0x7FFF.U(15.W)
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
      io.framebufferWriteAddr := framebufferIndex
      io.framebufferWriteData := gameboy.io.ppu.pixel
      framebufferX := framebufferX + 1.U
    }
  }

  io.cartridge <> gameboy.io.cartridge
}