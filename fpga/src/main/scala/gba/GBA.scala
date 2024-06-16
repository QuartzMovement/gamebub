package gba

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import gba.cpu.ARM7TDMI
import gba.mem.{BusAccessWidth, BusArbiter, BusTarget, SimpleRam, TargetInterface}
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

    /// Keypad state
    val keypad = Input(new Keypad.State)

    /// BIOS ROM access
    val biosRom = new BiosRomAccess
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
    BusTarget("Unmapped", 0xF.U(4.W), BusAccessWidth.Word),
  )))
  bus.io.enable := io.enable
  for (i <- 0 until 12) {
    bus.io.targetPort(i).dataRead := 0.U
    bus.io.targetPort(i).done := true.B
  }
  val busArbiter = Module(new BusArbiter(5))
  busArbiter.io.enable := io.enable
  bus.io.initiatorPort <> busArbiter.io.outputPort

  // MMIO Bus
  val mmio = Module(new MMIO(numTargets = 4))
  mmio.io.enable := io.enable
  bus.io.targetPort(3) <> mmio.io.mem

  // BIOS
  val bios = Module(new Bios)
  bios.io.enable := io.enable
  bios.io.access <> io.biosRom
  bus.io.targetPort(0) <> bios.io.target

  // Work RAMs
  val ewram = Module(new SimpleRam("EWRAM", 256 * 1024, 16.W, waitStates = 2))
  ewram.io.enable := io.enable
  bus.io.targetPort(1) <> ewram.io.target
  val iwram = Module(new SimpleRam("IWRAM", 32 * 1024, 32.W))
  iwram.io.enable := io.enable
  bus.io.targetPort(2) <> iwram.io.target

  bus.io.targetPort(7) <> io.cartRom

  // CPU
  val cpu = Module(new ARM7TDMI)
  cpu.io.enable := io.enable
  cpu.io.FIQ := false.B
  busArbiter.io.inputPorts(4) <> cpu.io.mem

  // Interrupt manager
  val interrupt = Module(new Interrupt)
  interrupt.io.enable := io.enable
  mmio.targets(0) <> interrupt.io.mmio
  cpu.io.IRQ := interrupt.io.irq
  interrupt.io.peripheralIrq := 0.U.asTypeOf(new Interrupt.Flags)

  // PPU
  val ppu = Module(new Ppu)
  ppu.io.enable := io.enable
  io.ppu := ppu.io.output
  bus.io.targetPort(4) <> ppu.io.paletteRamTarget
  bus.io.targetPort(5) <> ppu.io.vramTarget
  bus.io.targetPort(6) <> ppu.io.oamTarget
  mmio.targets(1) <> ppu.io.mmio
  interrupt.io.peripheralIrq.vblank := ppu.io.irqVblank
  interrupt.io.peripheralIrq.hblank := ppu.io.irqHblank
  interrupt.io.peripheralIrq.vcount := ppu.io.irqVcount

  // Keypad input
  val keypad = Module(new Keypad)
  keypad.io.enable := io.enable
  keypad.io.state := io.keypad
  mmio.targets(2) <> keypad.io.mmio

  // DMA
  val dma = Module(new Dma)
  dma.io.enable := io.enable
  dma.io.triggerHblank := ppu.io.dmaTriggerHblank
  dma.io.triggerVblank := ppu.io.dmaTriggerVblank
  dma.io.triggerVideo := ppu.io.dmaTriggerVideo
  mmio.targets(3) <> dma.io.mmio
  interrupt.io.peripheralIrq.dma := dma.io.irq.asUInt
  for (i <- 0 until 4) {
    busArbiter.io.inputPorts(i) <> dma.io.busInitiator(i)
  }
}
