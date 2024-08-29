package gba

import chisel3._
import chisel3.util._
import gba.ppu.PpuOutput
import lib.log.Logger

/// Game Boy Player support
///
/// Handles detection (based on PPU output), and communication.
/// The only "special feature" is rumble support.
class GameBoyPlayer extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    /// Raw PPU video output
    val ppu = Input(new PpuOutput)

    /// Override keypad input (during detection)
    val detected = Output(Bool())
    val keypadOverride = Output(UInt(10.W))
  })
  val logger = Logger("player", enable = io.enable)

  // "Game Boy Player" screen detection
  val regDetected = RegInit(false.B)
  val regDetectKeypadCounter = RegInit(0.U(2.W))
  val regScanline = Reg(UInt(8.W))
  val regDetectedSoFar = RegInit(false.B)
  val regDetectLineWhite = Reg(Bool())
  val regDetectLinePurple = Reg(Bool())
  val regDetectHash = Reg(UInt(16.W))
  val hashes = VecInit(Seq(
    0xc648, 0xfc1c, 0x4d01, 0x7128, 0xc994, 0x2f8f, 0x26ee, 0x5425, 0xe8e0,
    0xdd85, 0xe666, 0xf471, 0xd51e, 0xa1f3, 0xe597, 0xaf73, 0x4d28, 0x43e2,
    0xd912, 0xeedc, 0x6a20, 0x02de, 0xe188, 0xf14d, 0x2707, 0xba23, 0x4450,
    0x2ba6, 0xa61e, 0xd978, 0xff10, 0xff10, 0xfcfc, 0xa181, 0x8522, 0x2b94,
    0x1ba6, 0xec2c, 0x4810, 0x15c2, 0xe1d5, 0xb818, 0x3f09, 0x349e, 0x3d81,
  ).map(x => x.U(16.W)))
  when (!io.enable) {
  } .elsewhen (io.ppu.vblank) {
    regScanline := 0.U
    regDetectLineWhite := true.B
    regDetectLinePurple := true.B
    regDetectHash := 0.U
    regDetectedSoFar := true.B
    when (!RegNext(io.ppu.vblank)) {
      regDetected := regDetectedSoFar
      when (regDetectedSoFar) {
        regDetectKeypadCounter := regDetectKeypadCounter + 1.U
        when (regDetectKeypadCounter === 2.U) {
          regDetectKeypadCounter := 0.U
        }
        logger.crit("Detected Game Boy Player screen")
      }
    }
  } .elsewhen (io.ppu.hblank) {
    when (!RegNext(io.ppu.hblank)) {
      // Check whether the scanline that just finished is valid.
      // First 56 lines are all white
      // Next 45 lines have the logo (all purpleish, with B >= R >= G)
      // Last 59 lines are all white
      val shouldBeBlank = (regScanline < 56.U) || (regScanline >= 101.U)
      when (shouldBeBlank && !regDetectLineWhite) {
        regDetectedSoFar := false.B
      }
      when (!regDetectLinePurple) {
        regDetectedSoFar := false.B
      }
      when (regScanline >= 56.U && regScanline < 101.U) {
        when (regDetectHash =/= hashes((regScanline - 56.U)(5, 0))) {
          regDetectedSoFar := false.B
        }
      }

      regScanline := regScanline + 1.U
      regDetectLineWhite := true.B
      regDetectLinePurple := true.B
      regDetectHash := 0.U
    }
  } .elsewhen (io.ppu.valid) {
    val pixelR = io.ppu.pixel(4, 0)
    val pixelG = io.ppu.pixel(9, 5)
    val pixelB = io.ppu.pixel(14, 10)
    when (!(io.ppu.pixel === 0x7FFF.U)) {
      regDetectLineWhite := false.B
    }
    when (!(pixelB >= pixelR && pixelR >= pixelG)) {
      regDetectLinePurple := false.B
    }
    // Weak checksum. TODO replace with CRC
    regDetectHash := regDetectHash + io.ppu.pixel
  }

  // Output the detection keypad sequence:
  // 2 frames of no keys pressed, then 1 frame of {left, right, up, down} all pressed
  io.detected := regDetected
  io.keypadOverride := Mux(regDetectKeypadCounter === 2.U, 0x0F0.U, 0x000.U)
}