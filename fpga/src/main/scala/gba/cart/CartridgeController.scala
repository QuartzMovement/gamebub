package gba.cart

import chisel3._
import chisel3.util._
import gba.cart.CartridgeController.State
import gba.{MmioMap, MmioTarget}
import gba.mem.TargetInterface
import lib.log.Logger

object CartridgeController {
  object State extends ChiselEnum {
    val Idle = Value
    /// nCS goes low
    ///   Non-Seq: Can be extended by wait state
    val RomStage0 = Value
    /// nCS still low, nRD/nWR goes low (WS
    ///   Non-Seq: Can be extended by wait state
    ///       Seq: Can be extended by wait state
    val RomStage1 = Value
    /// nRD/nWR goes high
    ///  * if there's a request:
    //       start it (nextState: Rom1), keep nCS low.
    ///  * if no request, nCS goes high too
    val RomStage2 = Value

    /// nCS2 goes low
    /// ADDR put on bus
    /// For write, DATA put on bus
    /// On the *falling edge*: nRD/nRW goes low
    val RamStage0 = Value
    /// nCS2, nRD/nWR still low
    /// Can be extended by wait state
    val RamStage1 = Value
    /// nRD/nRW goes high
    ///  * if there's a request:
    ///      start it (next state: Rom0), keep nCS2 low
    ///  * if no request: on the *falling edge* nCS2 goes high.
    val RamStage2 = Value
  }
}

class WaitstateControl extends Bundle {
  // Cartridge type (read-only): false for GBA, true for CGB
  val isCgbCart = Bool()
  // Enable prefetch buffer
  // TODO: implement
  val prefetch = Bool()
  val _unused = UInt(1.W)
  // Phi clock output (Disable, 4 MHz, 8 MHz, 16 MHz)
  val phi = UInt(2.W)
  // ROM 2 wait states (sequential): 8, 1
  val ws2Next = UInt(1.W)
  // ROM 2 wait states (non-sequential): 4, 3, 2, 8
  val ws2First = UInt(2.W)
  // ROM 1 wait states (sequential): 4, 1
  val ws1Next = UInt(1.W)
  // ROM 1 wait states (non-sequential): 4, 3, 2, 8
  val ws1First = UInt(2.W)
  // ROM 0 wait states (sequential): 2, 1
  val ws0Next = UInt(1.W)
  // ROM 0 wait states (non-sequential): 4, 3, 2, 8
  val ws0First = UInt(2.W)
  // SRAM wait states: 4, 3, 2, 8
  val sram = UInt(2.W)
}

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

  val state = RegInit(State.Idle)
  val regReadData = Reg(UInt(16.W))
  val isRequestDone = WireDefault(false.B)
  val regWaitControl = RegInit(0.U.asTypeOf(new WaitstateControl))
  val waitRomFirst = Seq(regWaitControl.ws0First, regWaitControl.ws1First, regWaitControl.ws2First)
  val waitRomNext = Seq(regWaitControl.ws0Next, regWaitControl.ws1Next, regWaitControl.ws2Next)

  io.mmio <> MmioMap(
    // WAITCNT
    // TODO make sure Cartridge type always reads as 0
    0x204 -> MmioMap.Entry.rw(regWaitControl),
  )

  // Default target bus state
  for (x <- Seq(io.busTargetRom0, io.busTargetRom1, io.busTargetRom2)) {
    x.done := isRequestDone
    x.dataRead := regReadData
  }
  io.busTargetRam.done := false.B
  io.busTargetRam.dataRead := regReadData

  // Cartridge port
  io.cartridge.phi := 0.U  // TODO
  io.cartridge.nWR := 1.U
  io.cartridge.nRD := 1.U
  io.cartridge.nCS := 1.U
  io.cartridge.ADLoOut := DontCare
  io.cartridge.ADLoDir := 0.U
  io.cartridge.AHiOut := DontCare
  io.cartridge.AHiDir := 0.U
  io.cartridge.nCS2 := 1.U
  io.cartridge.reqStart := false.B
  io.cartridge.reqRom := DontCare
  io.cartridge.reqAddress := DontCare
  io.cartridge.reqWrite := DontCare
  io.cartridge.reqEnd := false.B

  // ROM targets
  val romTargets = Seq(io.busTargetRom0, io.busTargetRom1, io.busTargetRom2)
  val romRequests = romTargets.map(_.request)
  val hasRomRequest = VecInit(romRequests).asUInt.orR
  val romRequestAddress = Mux1H(romRequests, romTargets.map(_.address(24, 1)))
  val romRequestWrite = Mux1H(romRequests, romTargets.map(_.write))
  val romRequestSequential = Mux1H(romRequests, romTargets.map(_.sequential))
  val romRequestDataWrite = Mux1H(romRequests, romTargets.map(_.dataWrite))
  val ramTarget = io.busTargetRam
  val currentRequestPort = Reg(UInt(3.W))
  val currentAddress = Reg(UInt(24.W))
  val currentIsWrite = Reg(Bool())
  val waitCounter = Reg(UInt(3.W))

  switch (state) {
    is (State.Idle) {
      when (hasRomRequest) {
        logger.debug(cf"Start Rom(${VecInit(romRequests).asUInt}%b request addr=${romRequestAddress << 1}%x wr=$romRequestWrite")
        io.cartridge.ADLoOut := romRequestAddress(15, 0)
        io.cartridge.ADLoDir := true.B
        io.cartridge.AHiOut := romRequestAddress(23, 16)
        io.cartridge.AHiDir := true.B

        io.cartridge.reqStart := true.B
        io.cartridge.reqRom := true.B
        io.cartridge.reqAddress := romRequestAddress
        io.cartridge.reqWrite := romRequestWrite

        when (io.enable) {
          state := State.RomStage0
          currentAddress := romRequestAddress
          currentIsWrite := romRequestWrite
          currentRequestPort := VecInit(romRequests).asUInt

          // Initial burst wait: 0 [extra] cycles if total waits is 2,
          // otherwise 1.
          val wait = Mux1H(romRequests, waitRomFirst)
          when (wait === 2.U) {
            waitCounter := 0.U
          } .otherwise {
            waitCounter := 1.U
          }
        }
      } .elsewhen (ramTarget.request) {
        logger.debug(cf"Start Ram request addr=${ramTarget.address(15, 0)}%x wr=${ramTarget.write}")

        // TODO: should we put ADDR on the bus early?

        // TODO: Note that reqStart will be asserted for multiple cycles if io.enable is false
        io.cartridge.reqStart := true.B
        io.cartridge.reqRom := false.B
        io.cartridge.reqAddress := ramTarget.address(15, 0)
        io.cartridge.reqWrite := ramTarget.write

        when (io.enable) {
          state := State.RamStage0
          currentAddress := ramTarget.address
          currentIsWrite := ramTarget.write
        }
      }
    }
    is (State.RomStage0) {
      io.cartridge.nCS := 0.U
      io.cartridge.ADLoOut := currentAddress(15, 0)
      io.cartridge.ADLoDir := true.B
      io.cartridge.AHiOut := currentAddress(23, 16)
      io.cartridge.AHiDir := true.B

      val wait = Mux1H(currentRequestPort, waitRomFirst)
      when (currentIsWrite && waitCounter === 0.U && wait =/= 2.U) {
        // If this is the second cycle of a two cycle initial wait, set WDATA output.
        io.cartridge.ADLoDir := true.B
        io.cartridge.ADLoOut := romRequestDataWrite
      }

      when (io.enable) {
        waitCounter := waitCounter - 1.U
        when (waitCounter === 0.U) {
          state := State.RomStage1
          waitCounter := VecInit(1.U, 0.U, 0.U, 5.U)(wait)
        }
      }
    }
    is (State.RomStage1) {
      io.cartridge.nCS := 0.U
      when (currentIsWrite) {
        io.cartridge.nWR := 0.U
        io.cartridge.ADLoDir := true.B
        io.cartridge.ADLoOut := romRequestDataWrite
      } .otherwise {
        io.cartridge.nRD := 0.U
        io.cartridge.ADLoDir := false.B
      }
      io.cartridge.AHiOut := currentAddress(23, 16)
      io.cartridge.AHiDir := true.B

      io.cartridge.reqEnd := waitCounter === 0.U
      when (io.enable) {
        waitCounter := waitCounter - 1.U
        when (waitCounter === 0.U) {
          state := State.RomStage2
          regReadData := io.cartridge.ADLoIn
        }
      }
    }
    is (State.RomStage2) {
      // nRD/nWR goes back high
      isRequestDone := true.B
      io.cartridge.AHiOut := currentAddress(23, 16)
      io.cartridge.AHiDir := true.B
      when (currentIsWrite) {
        io.cartridge.ADLoDir := true.B
        io.cartridge.ADLoOut := romRequestDataWrite
      }

      val atPageEnd = currentAddress(15, 0).andR  // All 1s, highest address at page end
      when (hasRomRequest && romRequestSequential && !atPageEnd) {
        logger.debug(cf"Continue rom request")
        // Starting a new, sequential request
        // Keep nCS low
        io.cartridge.nCS := 0.U

        io.cartridge.reqStart := true.B
        io.cartridge.reqRom := true.B
        io.cartridge.reqAddress := romRequestAddress
        io.cartridge.reqWrite := romRequestWrite

        when (io.enable) {
          state := State.RomStage1
          currentAddress := romRequestAddress
          currentIsWrite := romRequestWrite

          val wait = Mux1H(currentRequestPort, waitRomNext)
          when (wait === 1.U) {
            waitCounter := 0.U  // 1 wait state
          } .otherwise {
            waitCounter := Mux1H(currentRequestPort, Seq(1.U, 3.U, 7.U))
          }
        }
      } .otherwise {
        // Not getting a sequential request this cycle. End burst.
        // nCS goes back high
        logger.debug(cf"End rom request")

        when (io.enable) {
          state := State.Idle
        }
      }
    }
    is (State.RamStage0) {
      io.cartridge.nCS2 := 0.U
      io.cartridge.ADLoOut := currentAddress(15, 0)
      io.cartridge.ADLoDir := true.B
      // XXX: nRD/nWR are supposed to go low on the *falling* edge of this cycle
      when (currentIsWrite) {
        io.cartridge.nWR := 0.U
        io.cartridge.ADLoDir := true.B
        io.cartridge.ADLoOut := ramTarget.dataWrite
      } .otherwise {
        io.cartridge.nRD := 0.U
      }

      when (io.enable) {
        state := State.RamStage1
        waitCounter := VecInit(2.U, 1.U, 0.U, 6.U)(regWaitControl.sram)
      }
    }
    is (State.RamStage1) {
      io.cartridge.nCS2 := 0.U
      io.cartridge.ADLoOut := currentAddress(15, 0)
      io.cartridge.ADLoDir := true.B
      when (currentIsWrite) {
        io.cartridge.nWR := 0.U
        io.cartridge.ADLoDir := true.B
        io.cartridge.ADLoOut := ramTarget.dataWrite
      } .otherwise {
        io.cartridge.nRD := 0.U
      }

      io.cartridge.reqEnd := waitCounter === 0.U
      when (io.enable) {
        waitCounter := waitCounter - 1.U
        when (waitCounter === 0.U) {
          state := State.RamStage2
          regReadData := io.cartridge.AHiIn
        }
      }
    }
    is (State.RamStage2) {
      ramTarget.done := true.B
      // nRD/NRW goes back high
      io.cartridge.ADLoOut := currentAddress(15, 0)
      io.cartridge.ADLoDir := true.B
      when (currentIsWrite) {
        io.cartridge.ADLoDir := true.B
        io.cartridge.ADLoOut := ramTarget.dataWrite
      }
      when (ramTarget.request && ramTarget.sequential) {
        logger.debug(cf"Continue ram request")
        // Starting a new "sequential" request -- nCS2 stays low
        io.cartridge.nCS2 := 0.U
        // XXX: in a burst, the next ADDR is put on the bus in the *falling* edge of this cycle
        // ... but we don't do that here. It's probably fine to just put it on the next rising
        // edge, because that's the timing of the first request in a burst anyway.

        io.cartridge.reqStart := true.B
        io.cartridge.reqRom := false.B
        io.cartridge.reqAddress := ramTarget.address(15, 0)
        io.cartridge.reqWrite := ramTarget.write

        when (io.enable) {
          state := State.RamStage0
          currentAddress := ramTarget.address
          currentIsWrite := ramTarget.write
        }
      } .otherwise {
        // Not getting another request this cycle, end burst.
        // nCS2 goes back high on the *falling* edge
        //
        // XXX: we actually keep nCS2 low here, to ensure it's low for a bit
        // of time after nRD/nWR go back high.
        // Since the next state is idle, we're guaranteed to get at least
        // one cycle of nCS2 being high (next cycle) before the next request,
        // so this is probably fine.
        io.cartridge.nCS2 := 0.U
        logger.debug(cf"End ram request")

        when (io.enable) {
          state := State.Idle
        }
      }
    }
  }
}
