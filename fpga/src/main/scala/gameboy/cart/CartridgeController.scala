package gameboy.cart

import chisel3._

import gameboy.Clocker
import gameboy.cart.CartridgeController.BusRequester

object CartridgeController {
  object BusRequester extends ChiselEnum {
    /// CPU is controlling the bus
    val cpu = Value
    /// OAM DMA is controlling the bus
    val oamDma = Value
    /// CGB VRAM DMA is controlling the bus
    val vramDma = Value
  }
}

class CartridgeController extends Module {
  val io = IO(new Bundle {
    val clocker = Input(new Clocker)
    // Whether we're on the last (system clock) cycle of an M-clock cycle.
    val lastClockCycle = Input(Bool())

    /// Cartridge interface
    val cartridge = new CartridgeInterface

    // Bus interface
    val busRequester = Input(CartridgeController.BusRequester())
    val busEnable = Input(Bool())
    val busAddress = Input(UInt(16.W))
    val busWrite = Input(Bool())
    // 1 for ROM, 0 for RAM
    val busIsRom = Input(Bool())
    val busDataRead = Output(UInt(8.W))
    val busDataWrite = Input(UInt(8.W))
  })

  // Physical interface
  // Old and incorrect comment:
  // This t-cycle logic works with HDMA too, even though it's 2x faster,
  // because HDMA always reads, never writes
  val doWrite = io.busWrite && (io.clocker.tCycle === 1.U || io.clocker.tCycle === 2.U)
  io.cartridge.phi := false.B  // TODO
  io.cartridge.nWR := ~doWrite
  io.cartridge.nRD := doWrite
  io.cartridge.nCS := io.busIsRom
  io.cartridge.resetOut := true.B  // TODO
  io.cartridge.address := io.busAddress
  io.cartridge.dataOut := io.busDataWrite
  io.busDataRead := io.cartridge.dataIn
  io.cartridge.dataDir := doWrite // Output if writing


  // Non-physical interface

  /// Whether a request is active this M-cycle
  val regActive = RegInit(false.B)
  /// Whether the next (module) cycle is the deadline for the current access
  val deadline = Wire(Bool())
  io.cartridge.reqStart := false.B
  io.cartridge.reqRom := io.busIsRom
  io.cartridge.reqWrite := io.busWrite
  io.cartridge.reqAddress := io.busAddress
  io.cartridge.reqEnd := regActive && deadline
  io.cartridge.reqDataWrite := io.busDataWrite

  when (io.clocker.enable && regActive && deadline) {
    regActive := false.B
  }
  when (io.busEnable && io.clocker.tCycle === 0.U) {
    regActive := true.B

    when (!regActive && !reset.asBool) {
      // Ensure that regStart isn't asserted for multiple cycles if
      // clocker.enable is false. If we depended directly on clocker.enable,
      // it would cause a combinatorial cycle in the outer modules.
      io.cartridge.reqStart := true.B
    }
  }

  deadline := io.lastClockCycle
  when (io.busRequester === BusRequester.vramDma) {
    deadline := io.clocker.counter8Mhz === 3.U
  }
}
