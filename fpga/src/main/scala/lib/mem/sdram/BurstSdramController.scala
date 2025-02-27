package lib.mem.sdram

import chisel3._
import chisel3.util._
import lib.mem.{MemoryInterface, PipelineMemoryInterface}
import lib.mem.sdram.BurstSdramController.{Address, Command, State}

object BurstSdramController {
  case class Config(
    /** Real clock frequency (Hz) */
    clockFrequency: Int,

    /** Physical address width */
    addressWidth: Int = 13,
    /** Physical data width (word size) */
    dataWidth: Int = 16,
    /** Bank address width */
    bankWidth: Int = 2,
    /** Row address width */
    rowWidth: Int = 13,
    /** Column address width */
    columnWidth: Int = 9,

    /** The burst length in words. May be 1, 2, 4, or 8. */
    burstLength: Int = 2,
    /** CAS latency (2 or 3) */
    casLatency: Int = 2,

    /** Chip initialization pause time (ns) */
    timeInit: Int = 200_000,
    /** Mode register set cycle time (ns) */
    timeRsc: Int = 15,
    /** Active to Active command delay time (ns) */
    timeRc: Int = 60,
    /** Active to Read/Write delay time (ns) */
    timeRcd: Int = 18,
    /** Precharge to Active delay time (ns) */
    timeRp: Int = 18,
    /** Write recovery time (ns) */
    timeWr: Int = 15,
    /** Refresh period (for all rows) (ns) */
    timeRef: Int = 64_000_000,

    /** Initialization refresh cycle count. */
    initRefreshCount: Int = 8,
  ) {
    /** The physical data bus width (in bytes). */
    val dataWidthBytes: Int = dataWidth / 8

    /** The logical address width. */
    val logicalAddressWidth: Int = new Address(this).getWidth

    /** The logical data width, considering burst length. */
    val logicalDataWidth: Int = dataWidth * burstLength

    /** Clock cycle time in nanoseconds */
    val clockPeriod: Double = 1000000000 / clockFrequency

    /** Cycles to wait during initial initialization. */
    val initDuration = (timeInit / clockPeriod).ceil.toInt

    /** Cycles to wait during mode set. */
    val modeDuration = (timeRsc / clockPeriod).ceil.toInt

    /** Cycles to wait during auto-refresh. */
    val refreshDuration = (timeRc / clockPeriod).ceil.toInt

    /** Cycles to wait during precharge.  */
    val prechargeDuration = (timeRp / clockPeriod).ceil.toInt

    /** Cycles to wait during row activation. */
    val activeDuration = (timeRcd / clockPeriod).ceil.toInt

    /** Cycles to wait during a write. */
    val writeDuration = burstLength + ((timeWr + timeRp) / clockPeriod).ceil.toInt

    /** Cycles to wait during a read. */
    val readDuration = casLatency + burstLength

    /** Maximum number of clock cycles between auto-refresh commands. */
    val refreshInterval = ((timeRef / (1 << rowWidth)) / clockPeriod).floor.toInt

    /** Width of the command duration counter (overestimate) */
    val waitCounterWidth = log2Ceil(timeInit) + 1

    /** Value of the mode register. */
    def mode: UInt = Cat(
      // Reserved (3 bits)
      0.U(3.W),
      // Write mode. 0: Burst read and burst write, 1: Single write
      0.U(1.W),
      // Reserved (2 bits)
      0.U(2.W),
      // CAS latency
      casLatency.U(3.W),
      // Burst type. 0: Sequential, 1: Interleaved
      0.U(1.W),
      // Burst length (words)
      log2Ceil(burstLength).U(3.W),
    )
    assert(mode.getWidth <= addressWidth)
  }

  /// SDRAM commands, treated as (CS, RAS, CAS, WE).
  private object Command extends ChiselEnum {
    val mode = Value
    val refresh = Value
    val precharge = Value
    val active = Value
    val write = Value
    val read = Value
    val burstStop = Value
    val nop = Value
    val deselect = Value
  }

  /// States of the controller.
  private object State extends ChiselEnum {
    val init = Value
    val mode = Value
    val idle = Value
    val active = Value
    val write = Value
    val read = Value
    val refresh = Value
    // TODO: support self-refresh mode?
  }

  private class Address(config: Config) extends Bundle {
    val bank = UInt(config.bankWidth.W)
    val row = UInt(config.rowWidth.W)
    val column = UInt(config.columnWidth.W)
    val _word = UInt(log2Ceil(config.dataWidthBytes).W)
  }
}

/// SDRAM controller that tries to keep bursts active
class BurstSdramController(config: BurstSdramController.Config) extends Module {
  val io = IO(new Bundle {
    /** Signals to the SDRAM chip. */
    val signals = new Signals(
      addressWidth = config.addressWidth,
      dataWidth = config.dataWidth,
      bankWidth = config.bankWidth
    )

    /** Standard memory interface to consumers. */
    val mem = new PipelineMemoryInterface(
      addressWidth = config.logicalAddressWidth,
      dataWidth = config.logicalDataWidth
    )
  })

  private val nextState = Wire(State())
  private val nextCommand = Wire(Command())

  private val regState = RegNext(nextState, State.init)
  private val regCommand = RegNext(nextCommand, Command.nop)
  val regRefreshCounter = RegInit(config.initRefreshCount.U)
  /** Address of the current access. */
  private val regAccessAddress = Reg(new Address(config))
  /** Whether the current access is a write. */
  val regAccessWrite = Reg(Bool())
  /** Data for the access. */
  val regData = Reg(Vec(config.burstLength, UInt(config.dataWidth.W)))

  val regDelayCounter = RegInit(0.U(config.waitCounterWidth.W))
  when (nextState =/= regState) {
    regDelayCounter := 0.U
  } .otherwise {
    regDelayCounter := regDelayCounter + 1.U
  }
  val (_, refreshOverflow) = Counter(0 until config.refreshInterval)
  when (refreshOverflow) {
    regRefreshCounter := regRefreshCounter + 1.U
  } .elsewhen (nextState === State.refresh && regState =/= State.refresh) {
    regRefreshCounter := regRefreshCounter - 1.U
  }

  // Request intake handling
  val canAcceptRequest = WireDefault(false.B)
  val regRequestPending = RegInit(false.B)
  val doRequest = WireDefault(regRequestPending)
  when (io.mem.ready && io.mem.enable) {
    regAccessAddress := io.mem.address.asTypeOf(regAccessAddress)
    regAccessWrite := io.mem.isWrite
    regRequestPending := true.B
    doRequest := true.B
  }
  when (nextState === State.active) {
    regRequestPending := false.B
  }

  val initPause = WireDefault(false.B)
  val modeDone = regDelayCounter === (config.modeDuration - 1).U
  val refreshDone = regDelayCounter === (config.refreshDuration - 1).U
  val activeDone = regDelayCounter === (config.activeDuration - 1).U
  val writeDone = regDelayCounter === (config.writeDuration - 1).U
  val readDone = regDelayCounter === (config.readDuration - 1).U

  val doRefresh = regRefreshCounter > 0.U

  when (regState === State.read || regState === State.write) {
    regData := io.signals.dataIn +: regData.init
  }

  nextState := regState
  nextCommand := Command.nop

  switch (regState) {
    is (State.init) {
      canAcceptRequest := true.B
      // 1. Initial pause (200 microseconds). Hold DKM and CKE high.
      // 2. Precharge all banks (keep A10 high)
      // 3. Set mode register
      // 4. 8 auto-refresh cycles (before or after mode register)
      val counterStartPrecharge = config.initDuration - 1
      val counterStartMode = counterStartPrecharge + config.prechargeDuration
      initPause := regDelayCounter <= counterStartPrecharge.U
      when (regDelayCounter === counterStartPrecharge.U) {
        // Precharge all banks (keep A10 high).
        nextCommand := Command.precharge
      } .elsewhen (regDelayCounter === counterStartMode.U) {
        nextState := State.mode
        nextCommand := Command.mode
        regRefreshCounter := config.initRefreshCount.U
      }
    }
    is (State.mode) {
      canAcceptRequest := true.B

      when (modeDone) {
        nextState := State.idle
      }
    }
    is (State.idle) {
      canAcceptRequest := true.B

      when (doRefresh) {
        nextState := State.refresh
        nextCommand := Command.refresh
      } .elsewhen (doRequest) {
        nextState := State.active
        nextCommand := Command.active

        regAccessAddress := io.mem.address.asTypeOf(regAccessAddress)
        regAccessWrite := io.mem.isWrite
      }
    }
    is (State.active) {
      // TODO ensure we wait tRC between actives and refreshes
      when (activeDone) {
        when (regAccessWrite) {
          nextState := State.write
          nextCommand := Command.write
          regData := io.mem.dataWrite.asTypeOf(regData)
        } .otherwise {
          nextState := State.read
          nextCommand := Command.read
        }
      }
    }
    is (State.write) {
      when (writeDone) {
        nextState := State.idle
        // TODO: ready could be high now
        // TODO: see about avoiding extra clock cycle before going to refresh or active
      }
    }
    is (State.read) {
      when (readDone) {
        nextState := State.idle
        // Note: ready can't be high until the next cycle
        // TODO: see about avoiding extra clock cycle before going to refresh or active
      }
    }
    is (State.refresh) {
      canAcceptRequest := true.B

      when (refreshDone) {
        when (doRequest) {
          nextState := State.active
          nextCommand := Command.active
        } .otherwise {
          nextState := State.idle
          // TODO: see about avoiding extra clock cycle before going to refresh or active
        }
      }
    }
  }

  io.signals.cke := 1.U
  io.signals.cs := regCommand.asUInt(3)
  io.signals.ras := regCommand.asUInt(2)
  io.signals.cas := regCommand.asUInt(1)
  io.signals.we := regCommand.asUInt(0)
  io.signals.dqm := Mux(initPause, "b11".U(2.W), "b00".U(2.W)) // TODO: support byte write strobe
  io.signals.bank := Mux(regState === State.mode, 0.U, regAccessAddress.bank)
  io.signals.address := MuxLookup(regState.asUInt, 0.U)(Seq(
    State.init.asUInt -> "b10000000000".U, // Keep A10 high to precharge all banks.
    State.mode.asUInt -> config.mode,
    State.active.asUInt -> regAccessAddress.row,
    State.read.asUInt -> (regAccessAddress.column | "b10000000000".U),
    State.write.asUInt -> (regAccessAddress.column | "b10000000000".U),
  ))
  io.signals.dataOut := regData.last
  io.signals.dataDir := regState === State.write

  io.mem.dataRead := regData.asUInt
  io.mem.ready := canAcceptRequest && !regRequestPending
}
