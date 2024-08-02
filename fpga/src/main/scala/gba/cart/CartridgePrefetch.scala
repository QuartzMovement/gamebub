package gba.cart

import chisel3._
import chisel3.util._
import gba.mem.TargetInterface
import lib.log.Logger

/**
 * Cartridge prefetch controller
 *
 * The purpose of prefetch is to keep ROM bursts going when non-cartridge
 * memory requests are occurring. Then, prefetched data is fed back to the bus
 * with zero wait states.
 *
 * Prefetch only activates for code (not data) requests from ROM. Any request
 * to the cartridge that isn't a code request for the next fetched address
 * in the buffer aborts prefetch.
 */
class CartridgePrefetch extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val prefetchEnabled = Input(Bool())

    val busTargetRom0 = new TargetInterface(16.W)
    val busTargetRom1 = new TargetInterface(16.W)
    val busTargetRom2 = new TargetInterface(16.W)
    val busTargetRamRequest = Input(Bool())

    val cartInitiatorRom = Flipped(new TargetInterface(16.W))
    val cartInitiatorRomRegion = Output(Vec(3, Bool()))
    /// Whether the cartridge controller should abort the current request
    val cartInitiatorAbortRequest = Output(Bool())
  })
  val logger = Logger("cart.prefetch")

  // Passing through the rom
  val romTargets = Seq(io.busTargetRom0, io.busTargetRom1, io.busTargetRom2)
  val romInitiator = io.cartInitiatorRom
  val romRequests = romTargets.map(_.request)
  val hasRomRequest = VecInit(romRequests).asUInt.orR
  val hasRamRequest = io.busTargetRamRequest
  romInitiator.request := hasRomRequest
  romInitiator.address := Mux1H(romRequests, romTargets.map(_.address(24, 1)))
  romInitiator.write := Mux1H(romRequests, romTargets.map(_.write))
  romInitiator.dataWrite := Mux1H(romRequests, romTargets.map(_.dataWrite))
  romInitiator.sequential := Mux1H(romRequests, romTargets.map(_.sequential))
  romInitiator.nextSeq := Mux1H(romRequests, romTargets.map(_.nextSeq))
  romInitiator.size := DontCare
  romInitiator.mask := DontCare
  romInitiator.isData := DontCare
  romInitiator.nextRequest := DontCare  // unused
  for (x <- romTargets) {
    x.done := romInitiator.done
    x.dataRead := romInitiator.dataRead
  }
  io.cartInitiatorRomRegion := VecInit(romRequests)
  io.cartInitiatorAbortRequest := false.B
}
