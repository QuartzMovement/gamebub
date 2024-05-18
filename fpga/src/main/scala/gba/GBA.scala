package gba

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import gba.cpu.{ARM7TDMI, BusAccessWidth}
import gba.mem.{BusTarget, SimpleRam, TargetInterface}
import gba.ppu.{Ppu, PpuOutput}

object GBA extends App {
  ChiselStage.emitSystemVerilogFile(new GBA, args)
}

class GBA extends Module {
  val io = IO(new Bundle {
    /// Global enable signal
    val enable = Input(Bool())

    /// Cartridge access
    val cartRom = Flipped(new TargetInterface(16.W))

    /// PPU video output
    val ppu = Output(new PpuOutput)
  })

  val bus = Module(new mem.Bus(Seq(
    BusTarget("BIOS", 0x0.U(4.W), BusAccessWidth.Word),
    BusTarget("EWRAM", 0x2.U(4.W), BusAccessWidth.Halfword),
    BusTarget("IWRAM", 0x3.U(4.W), BusAccessWidth.Word),
    BusTarget("I/O", 0x4.U(4.W), BusAccessWidth.Word),
    BusTarget("Palette Ram", 0x5.U(4.W), BusAccessWidth.Halfword),
    BusTarget("Video Ram", 0x6.U(4.W), BusAccessWidth.Halfword),
    BusTarget("OAM", 0x7.U(4.W), BusAccessWidth.Word),
    BusTarget("Cart ROM 0", (0x8 >> 1).U(3.W), BusAccessWidth.Halfword),
    BusTarget("Cart ROM 1", (0xA >> 1).U(3.W), BusAccessWidth.Halfword),
    BusTarget("Cart ROM 2", (0xC >> 1).U(3.W), BusAccessWidth.Halfword),
    BusTarget("Cart RAM", 0xE.U(4.W), BusAccessWidth.Byte),
  )))
  bus.io.enable := io.enable
  for (i <- 0 until 11) {
    bus.io.targetPort(i).dataRead := 0.U
    bus.io.targetPort(i).done := true.B
  }

  // MMIO Bus
  val mmio = Module(new MMIO(1))
  mmio.io.enable := io.enable
  bus.io.targetPort(3) <> mmio.io.mem

  // Temporary BIOS
  val bios = Module(new Bios)
  bios.io.enable := io.enable
  bus.io.targetPort(0) <> bios.io.target
  // Temporary RAMs
  val ewram = Module(new SimpleRam(256 * 1024, 16.W))
  ewram.io.enable := io.enable
  bus.io.targetPort(1) <> ewram.io.target
  val iwram = Module(new SimpleRam(32 * 1024, 32.W))
  iwram.io.enable := io.enable
  bus.io.targetPort(2) <> iwram.io.target

  bus.io.targetPort(7) <> io.cartRom

  // CPU
  val cpu = Module(new ARM7TDMI)
  cpu.io.enable := io.enable
  cpu.io.FIQ := false.B
  cpu.io.IRQ := false.B
  bus.io.initiatorPort <> cpu.io.mem

  // PPU
  val ppu = Module(new Ppu)
  ppu.io.enable := io.enable
  io.ppu := ppu.io.output
  bus.io.targetPort(4) <> ppu.io.paletteRamTarget
  bus.io.targetPort(5) <> ppu.io.vramTarget
  mmio.targets(0) <> ppu.io.mmio
}
