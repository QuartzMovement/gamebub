package platform.sim

import chisel3._
import _root_.circt.stage.ChiselStage
import gba._
import gba.mem.{SimpleRam, TargetInterface}
import gba.ppu.PpuOutput

object SimGba extends App {
  ChiselStage.emitSystemVerilogFile(new SimGba, args)
}

class SimGba extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val cartRom = Flipped(new TargetInterface(16.W))
    val ppu = Output(new PpuOutput)
    val keypad = Input(new Keypad.State)
  })

  val gba = Module(new GBA())
  gba.io.enable <> io.enable
  gba.io.cartRom <> io.cartRom
  gba.io.ppu <> io.ppu
  gba.io.keypad <> io.keypad

  // BIOS, to be filled in by verilator simulator
  val biosRom = {
    val rom = SyncReadMem(16 * 1024 / 4, UInt(32.W))
    // dontTouch: hack to ensure Chisel doesn't optimize the mem out
    val temp = dontTouch(WireDefault(false.B))
    when (temp) {
      rom.write(0.U, 0.U)
    }
    rom
  }
  gba.io.biosRom.data := biosRom.read(gba.io.biosRom.address, gba.io.biosRom.read)

  // EWRAM
  val ewram = Module(new SimpleRam("EWRAM", 256 * 1024, 16.W, waitStates = 2))
  ewram.io.enable := io.enable
  gba.io.ewram <> ewram.io.target
}