package platform.sim

import chisel3._
import _root_.circt.stage.ChiselStage
import gba._
import gba.cart.{EmulatedCartridge, EmulatedCartridgeDataAccess}
import gba.mem.SimpleRam
import gba.ppu.PpuOutput
import lib.log.Log

object SimGba extends App {
  Log.setDefaultLevel(Log.Level.Warning)

  ChiselStage.emitSystemVerilogFile(new SimGba, args)
}

class SimGba extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val emuCart = new EmulatedCartridgeDataAccess
    val ppu = Output(new PpuOutput)
    val keypad = Input(new Keypad.State)
  })

  val gba = Module(new GBA())
  gba.io.enable <> io.enable
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

  // Emulated cartridge
  val emuCart = Module(new EmulatedCartridge)
  gba.io.cartridge <> emuCart.io.interface
  io.emuCart <> emuCart.io.data
}