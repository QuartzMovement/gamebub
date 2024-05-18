package gba.cpu

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

/// ARM7TDMI-S compatible processor as found in the GBA
class ARM7TDMI extends Module {
  val io = IO(new Bundle {
    /// Global enable signal for emulation
    val enable = Input(Bool())
    /// Debug output
    val debug = Output(new CpuDebug())

    /// Memory bus interface
    val mem = new BusInterface
    /// **Active-High** fast interrupt request
    val FIQ = Input(Bool())
    /// **Active-High** interrupt request
    val IRQ = Input(Bool())
  })

  val enable = io.enable && io.mem.CLKEN

  ////////////////////////////////// Busses and Registers //////////////////////////////////
  val memAddrReg = Reg(UInt(32.W))
  val memReadDataReg = Reg(UInt(32.W))

  val aBus = Wire(UInt(32.W))
  val bBus = Wire(UInt(32.W))
  val cBus = Wire(UInt(32.W))
  val pcBus = Wire(UInt(32.W))
  val aluBus = Wire(UInt(32.W))
  val aluConditionOut = Wire(new ConditionFlags)
  val incrementerBus = Wire(UInt(32.W))
  val control = Wire(new ControlSignals)
  val cpsrBus = Wire(new ProgramStatusRegister)
//  printf(cf"CPSR: ${cpsrBus}\n")
  bBus := DontCare

  //////////////////////////////// Instruction Fetch & Decode //////////////////////////////
  val decodeUnit = Module(new Decoder)
  decodeUnit.io.enable := enable
  decodeUnit.io.advancePipeline := control.advancePipeline
  decodeUnit.io.flushPipeline := control.flushPipeline
  decodeUnit.io.readData := io.mem.RDATA
  decodeUnit.io.readAddress := memAddrReg(1, 0)
  decodeUnit.io.thumb := cpsrBus.thumb

  ////////////////////////////////////// Control Unit //////////////////////////////////////
  val controlUnit = Module(new Control)
  controlUnit.io.enable := enable
  controlUnit.io.nextInstruction := decodeUnit.io.decoded
  controlUnit.io.fiq := io.FIQ
  controlUnit.io.irq := io.IRQ
  control := controlUnit.io.signals
  when (control.busB === BusBValue.Immediate) {
    bBus := control.immediate
  }

  ///////////////////////////////////// Register File //////////////////////////////////////
  // 0-15: r0-r15
  // 16: 13_svc, 17: 14_svc,
  // 18: 13_abt, 19: 14_abt,
  // 20: 13_und, 21: 14_und,
  // 22: 13_irq, 23: 14_irq
  // 24-30: 8-14 _fiq
  val registers = RegInit(VecInit(Seq.fill(31)(0.U(32.W))))
  private def bankRegIndex(index: UInt, mode: CpuMode.Type = control.regBankMode): UInt = {
    val offset = WireDefault(0.U(5.W))
    when (mode === CpuMode.Fiq && index >= 8.U && index <= 14.U) {
      offset := (24 - 8).U(5.W)
    } .elsewhen (index === 13.U || index === 14.U) {
      when (mode === CpuMode.Supervisor) {
        offset := (16 - 13).U(5.W)
      } .elsewhen (mode === CpuMode.Abort) {
        offset := (18 - 13).U(5.W)
      } .elsewhen (mode === CpuMode.Undefined) {
        offset := (20 - 13).U(5.W)
      } .elsewhen (mode === CpuMode.Irq) {
        offset := (22 - 13).U(5.W)
      }
    }
    index + offset
  }

  val cpsr = RegInit((new ProgramStatusRegister).Lit(
    _.mode -> CpuMode.System,
    _.thumb -> false.B,
    _.irqDisable -> true.B,
    _.fiqDisable -> true.B,
    _.padding -> 0.U,
    _.cond -> (new ConditionFlags).Lit(
      _.n -> false.B,
      _.z -> false.B,
      _.c -> false.B,
      _.v -> false.B,
    ),
  ))
  val spsrVec = Reg(Vec(5, new ProgramStatusRegister))
  val spsrIndex = MuxLookup(control.regBankMode, 0.U)(Seq(
    CpuMode.Supervisor -> 0.U,
    CpuMode.Abort -> 1.U,
    CpuMode.Undefined -> 2.U,
    CpuMode.Irq -> 3.U,
    CpuMode.Fiq -> 4.U,
  ))
  val spsr = spsrVec(spsrIndex)
  val modeHasSpsr = (control.regBankMode =/= CpuMode.User) && (control.regBankMode =/= CpuMode.System)
  val modePrivileged = cpsr.mode =/= CpuMode.User
  val nextCpsr = WireDefault(cpsr)

  controlUnit.io.currentStatus := cpsr
  controlUnit.io.nextStatus := nextCpsr
  cpsrBus := cpsr
  val pc = registers(15)
  pcBus := pc
  aBus := registers(bankRegIndex(control.regReadA))
  when (control.busB === BusBValue.RegisterB) {
    bBus := registers(bankRegIndex(control.regReadB))
  } .elsewhen (control.busB === BusBValue.Cpsr) {
    bBus := cpsr.asUInt
  } .elsewhen (control.busB === BusBValue.Spsr) {
    when (modeHasSpsr) {
      bBus := spsr.asUInt
    } .otherwise {
      // Modes without SPSR apparently return CPSR on a read
      bBus := cpsr.asUInt
    }
  }
  cBus := registers(
    bankRegIndex(
      control.regReadC,
      Mux(control.regUserReadC, CpuMode.User, control.regBankMode))
  )
  when (enable) {
    when (control.regWriteEnable) {
//      printf(cf"  reg write [${control.regWriteIndex}] <- ${aluBus}%x\n")
      registers(
        bankRegIndex(
          control.regWriteIndex,
          Mux(control.regUserWrite, CpuMode.User, control.regBankMode)
        )) := aluBus
    }
    when (control.cpsrUpdateCond) {
      nextCpsr.cond := aluConditionOut
    }
    when (control.cpsrUpdateThumb) {
      nextCpsr.thumb := aBus(0)
    }
    when (control.cpsrUpdateFields(0) && modePrivileged) {
      nextCpsr.mode := suppressEnumCastWarning { aluBus(4, 0).asTypeOf(CpuMode()) }
      nextCpsr.thumb := aluBus(5)
      nextCpsr.fiqDisable := aluBus(6)
      nextCpsr.irqDisable := aluBus(7)
    }
    when (control.cpsrUpdateFields(1)) {
      nextCpsr.cond := aluBus(31, 28).asTypeOf(new ConditionFlags)
    }
    when (control.spsrUpdateFields(0) && modeHasSpsr) {
      spsr.mode := suppressEnumCastWarning { aluBus(4, 0).asTypeOf(CpuMode()) }
      spsr.thumb := aluBus(5)
      spsr.fiqDisable := aluBus(6)
      spsr.irqDisable := aluBus(7)
    }
    when (control.spsrUpdateFields(1) && modeHasSpsr) {
      spsr.cond := aluBus(31, 28).asTypeOf(new ConditionFlags)
    }
    when (control.cpsrRestore && modeHasSpsr) {
      nextCpsr := spsr
    }
    when (control.startException) {
      val newMode = control.regBankMode
      nextCpsr.mode := newMode
      nextCpsr.thumb := false.B
      nextCpsr.irqDisable := true.B
      when (newMode === CpuMode.Fiq) { // also in Reset
        nextCpsr.fiqDisable := true.B
      }
      spsr := cpsrBus
    }
    switch (control.pcNext) {
      is (PcNext.Incrementer) { pc := incrementerBus }
    }
    cpsr := nextCpsr
  }

  ///////////////////////////////////// Barrel Shifter /////////////////////////////////////
  val shifter = Module(new Shifter)
  shifter.io.in := bBus
  shifter.io.carryIn := cpsrBus.cond.c
  shifter.io.shiftKind := control.shiftKind
  shifter.io.shiftAmount := control.shiftImmediate
  shifter.io.latchShift := enable && control.shiftDoLatch
  shifter.io.useLatchedShift := control.shiftUseLatched

  ////////////////////////////////////////// ALU ///////////////////////////////////////////
  val alu = Module(new Alu)
  alu.io.a := aBus
  when (control.aluInAAlign4) {
    alu.io.a := aBus & "hFFFFFFFC".U(32.W)
  }
  alu.io.b := shifter.io.out
  alu.io.opcode := control.aluOpcode
  alu.io.flagIn := cpsrBus.cond
  alu.io.shifterCarry := shifter.io.carryOut
  aluBus := alu.io.out
  when (control.aluOutAlign4) {
    aluBus := alu.io.out & "hFFFFFFFC".U(32.W)
  } .elsewhen (cpsr.thumb && control.regWriteIndex === 15.U) {
    // Special behavior in THUMB: writes to r15 (branches) are force-aligned.
    aluBus := alu.io.out & "hFFFFFFFE".U(32.W)
  }
  aluConditionOut := alu.io.flagOut

  /////////////////////////////////////// Multiplier ///////////////////////////////////////
  val multiplier = Module(new Multiplier)
  multiplier.io.enable := enable
  multiplier.io.a := aBus
  multiplier.io.b := bBus
  multiplier.io.start := control.multiplyEnable
  multiplier.io.loadAccumulator := control.multiplyLoadAccumulator
  multiplier.io.accumulate := control.multiplyAccumulate
  multiplier.io.signed := control.multiplySigned
  multiplier.io.long := control.multiplyLong
  when (control.busB === BusBValue.MultiplyLo) {
    bBus := multiplier.io.outLo
  } .elsewhen (control.busB === BusBValue.MultiplyHi) {
    bBus := multiplier.io.outHi
  }
  controlUnit.io.multiplierDone := multiplier.io.done
  when (control.cpsrFromMultiply) {
    nextCpsr.cond.z := multiplier.io.outFlagZ
    nextCpsr.cond.n := multiplier.io.outFlagN
  }

  /////////////////////////////////////// Incrementer //////////////////////////////////////
  incrementerBus := memAddrReg + Mux(cpsrBus.thumb && !control.incrementerForceWord, 2.U, 4.U)

  ///////////////////////////////////////// IO Port ////////////////////////////////////////
  val currentMemReadWidth = Reg(BusAccessWidth())
  val lastMemReadWidth = Reg(BusAccessWidth())
  val lastMemReadAlign = Reg(UInt(2.W))
  io.mem.ADDR := memAddrReg
  switch (control.addressSource) {
    is (AddressSource.Incrementer) { io.mem.ADDR := incrementerBus }
    is (AddressSource.Pc) { io.mem.ADDR := pcBus }
    is (AddressSource.Alu) { io.mem.ADDR := aluBus }
    is (AddressSource.Immediate) { io.mem.ADDR := control.immediate }
  }
  when (enable) {
    memAddrReg := io.mem.ADDR
    currentMemReadWidth := io.mem.SIZE
    when (control.latchMemReadData) {
      lastMemReadWidth := currentMemReadWidth
      lastMemReadAlign := memAddrReg(1, 0)
      memReadDataReg := io.mem.RDATA
//      printf(cf"## cpu read: 0x${io.mem.RDATA}%x\n")
    }
  }
  val memWriteData = Wire(UInt(32.W))
  when (currentMemReadWidth === BusAccessWidth.Byte) {
    memWriteData := Fill(4, cBus(7, 0))
  } .elsewhen (currentMemReadWidth === BusAccessWidth.Halfword) {
    memWriteData := Fill(2, cBus(15, 0))
  } .otherwise {
    memWriteData := cBus
  }
  when (control.busB === BusBValue.MemReadData) {
    val readData = WireDefault(memReadDataReg)
    bBus := readData

    // For halfword and byte loads, mask out / sign extend bits.
    val maskValue = WireDefault(0.U(8.W))
    when (control.memReadDataSigned) {
      val signByte = Mux(
        lastMemReadWidth === BusAccessWidth.Halfword,
        lastMemReadAlign | 1.U,
        lastMemReadAlign,
      )
      maskValue := Fill(8, memReadDataReg(Cat(signByte, "b111".U(3.W))))
    }
    when (lastMemReadWidth === BusAccessWidth.Byte) {
      readData := Cat(
        Mux(lastMemReadAlign === 3.U, memReadDataReg(31, 24), maskValue),
        Mux(lastMemReadAlign === 2.U, memReadDataReg(23, 16), maskValue),
        Mux(lastMemReadAlign === 1.U, memReadDataReg(15, 8), maskValue),
        Mux(lastMemReadAlign === 0.U, memReadDataReg(7, 0), maskValue),
      )
    } .elsewhen (lastMemReadWidth === BusAccessWidth.Halfword) {
      readData := Cat(
        Mux(lastMemReadAlign(1), memReadDataReg(31, 16), Fill(2, maskValue)),
        Mux(!lastMemReadAlign(1), memReadDataReg(15, 0), Fill(2, maskValue)),
      )
    }
  }
  when (control.shiftByAddressAlign) {
    shifter.io.shiftAmount := lastMemReadAlign << 3
  }

  io.mem.WDATA := memWriteData
  io.mem.WRITE := control.memWrite
  io.mem.SIZE := control.memWidth
  io.mem.TRANS := control.memTransaction
  io.mem.LOCK := control.memLock
  io.mem.PROT := control.memProt

  ////////////////////////////////////////// Debug /////////////////////////////////////////
  io.debug.registers := VecInit(
    (0 until 16).map(i => registers(bankRegIndex(i.U)))
  )
  io.debug.cpsr := cpsr.asUInt
//  printf(cf" pc is ${pc}%x, addr is ${io.mem.ADDR}%x\n")
}

class ConditionFlags extends Bundle {
  /// Negative or less than
  val n = Bool()
  /// Zero
  val z = Bool()
  /// Carry or borrow or extend
  val c = Bool()
  /// Overflow
  val v = Bool()
}

class ProgramStatusRegister extends Bundle {
  /// [31:28]: Condition flags
  val cond = new ConditionFlags

  /// 20 bits of padding, always read as 0
  val padding = UInt(20.W)

  ///     7: IRQ disable
  val irqDisable = Bool()
  ///     6: FIQ disable
  val fiqDisable = Bool()
  ///     5: State bit
  val thumb = Bool()
  /// [4:0]: Mode bits
  val mode = CpuMode()
}

object CpuMode extends ChiselEnum {
  val User = Value("b10000".U(5.W))
  val Fiq = Value("b10001".U(5.W))
  val Irq = Value("b10010".U(5.W))
  val Supervisor = Value("b10011".U(5.W))
  val Abort = Value("b10111".U(5.W))
  val Undefined = Value("b11011".U(5.W))
  val System = Value("b11111".U(5.W))
}

class CpuDebug extends Bundle {
  val registers = Vec(16, UInt(32.W))
  val cpsr = UInt(32.W)
}