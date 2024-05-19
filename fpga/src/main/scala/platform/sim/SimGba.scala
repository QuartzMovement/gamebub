package platform.sim

import chisel3._
import _root_.circt.stage.ChiselStage
import gba._
import gba.mem.TargetInterface
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
}