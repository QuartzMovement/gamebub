package gba.cart

import chisel3._
import chisel3.util._
import gba.{MmioMap, MmioTarget}
import gba.mem.TargetInterface
import lib.log.Logger

class CartridgeController extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    /// Cartridge interface
    val cartridge = new CartridgeInterface

    // MMIO interface for WAITCNT (cartridge waitstate control and prefetch buffer)
    val mmio = new MmioTarget()

    // Memory bus target interfaces
    val busTargetRom0 = new TargetInterface(16.W)
    val busTargetRom1 = new TargetInterface(16.W)
    val busTargetRom2 = new TargetInterface(16.W)
    val busTargetRam = new TargetInterface(8.W)
  })
  val logger = Logger("cart")

  val regWaitControl = RegInit(0.U(16.W))
  io.mmio <> MmioMap(
    // WAITCNT
    0x204 -> MmioMap.Entry.rw(regWaitControl),
  )

  // TODO implement cartridge

  // Stubs
  for (x <- Seq(io.busTargetRom0, io.busTargetRom1, io.busTargetRom2, io.busTargetRam)) {
    x.done := true.B
    x.dataRead := 0.U
  }
  io.cartridge.phi := 0.U
  io.cartridge.nWR := 1.U
  io.cartridge.nRD := 1.U
  io.cartridge.nCS := 1.U
  io.cartridge.ADLoOut := 0.U
  io.cartridge.ADLoDir := 0.U
  io.cartridge.AHiOut := 0.U
  io.cartridge.AHiDir := 0.U
  io.cartridge.nCS2 := 1.U
  io.cartridge.reqStart := false.B
  io.cartridge.reqRead := DontCare
  io.cartridge.reqEnd := false.B
}
