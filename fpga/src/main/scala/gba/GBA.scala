package gba

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import gba.cpu.{ARM7TDMI, BusAccessWidth}

object GBA extends App {
  ChiselStage.emitSystemVerilogFile(new GBA, args)
}

class GBA extends Module {
  val io = IO(new Bundle {
    /// Global enable signal
    val enable = Input(Bool())
  })

  val bus = Module(new mem.Bus(Seq(
    // BIOS ROM
    0x0.U(4.W) -> BusAccessWidth.Word,
    // EWRAM
    0x2.U(4.W) -> BusAccessWidth.Halfword,
    // IWRAM
    0x3.U(4.W) -> BusAccessWidth.Word,
    // I/O
    0x4.U(4.W) -> BusAccessWidth.Word,
    // Palette Ram
    0x5.U(4.W) -> BusAccessWidth.Halfword,
    // Video Ram
    0x6.U(4.W) -> BusAccessWidth.Halfword,
    // OAM
    0x7.U(4.W) -> BusAccessWidth.Word,
    // Cartridge ROM 0
    (0x8 >> 1).U(3.W) -> BusAccessWidth.Halfword,
    // Cartridge ROM 1
    (0xA >> 1).U(3.W) -> BusAccessWidth.Halfword,
    // Cartridge ROM 2
    (0xC >> 1).U(3.W) -> BusAccessWidth.Halfword,
    // Cartridge RAM
    0xE.U(4.W) -> BusAccessWidth.Byte,
  )))
  bus.io.enable := io.enable
  for (i <- 0 until 11) {
    bus.io.targetPort(i).dataRead := DontCare
    bus.io.targetPort(i).valid := false.B
  }

  val cpu = Module(new ARM7TDMI)
  cpu.io.enable := io.enable
  cpu.io.FIQ := false.B
  cpu.io.IRQ := false.B
  bus.io.initiatorPort <> cpu.io.mem
}
