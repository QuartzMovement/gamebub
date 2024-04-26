package gba.cpu

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

object PcNext extends ChiselEnum {
  val Same = Value
  val Incrementer = Value
}

object AddressSource extends ChiselEnum {
  val Same = Value
  val Incrementer = Value
  val Pc = Value
  val Alu = Value
}

object BusBValue extends ChiselEnum {
  val RegisterB = Value
  val Immediate = Value
  val MemReadData = Value
}

class ControlSignals extends Bundle {
  /// True to start execution of the next instruction.
  val nextInstruction = Bool()
  val branch = Bool()

  val pcNext = PcNext()
  val addressSource = AddressSource()

  val regReadA = UInt(4.W)
  val regReadB = UInt(4.W)
  val regReadC = UInt(4.W)
  val regWriteIndex = UInt(4.W)
  val regWriteEnable = Bool()
  val updateConditionCodes = Bool()

  val busB = BusBValue()
  val immediate = UInt(12.W)

  val aluOpcode = AluOpcode()
  val shiftKind = ShiftKind()
  val shiftImmediate = UInt(5.W)
  val shiftDoLatch = Bool()
  val shiftUseLatched = Bool()

  val memTransaction = BusTransactionType()
  val memWrite = Bool()
  val memWidth = BusAccessWidth()
  val memProt = new BusProtectionType
  val latchMemReadData = Bool()
  val latchMemWriteData = Bool()
  val memReadDataSigned = Bool()
}

/// Control unit
class Control extends Module {
  val io = IO(new Bundle {
    /// Global enable
    val enable = Input(Bool())

    /// Control signals
    val signals = Output(new ControlSignals)
    /// Next instruction from the decoder
    val nextInstruction = Input(new DecodedInstruction)
    /// Current program status register
    val currentStatus = Input(new ProgramStatusRegister)
  })
  val control = io.signals

  val instruction = RegInit((new DecodedInstruction).Lit(
    _.condition -> Condition.Nv
  ))
  val stage = RegInit(0.U(5.W))
  val nextStage = WireDefault(stage)
  when (io.enable) {
    stage := nextStage
    when (control.branch) {
      instruction.condition := Condition.Nv
      stage := 0.U
    } .elsewhen (control.nextInstruction) {
      instruction := io.nextInstruction
      stage := 0.U
    }
  }
  val execute = Control.evaluateCondition(instruction.condition, io.currentStatus.cond)

  control.nextInstruction := false.B
  control.branch := false.B
  control.pcNext := PcNext.Same
  control.addressSource := AddressSource.Same
  control.regReadA := DontCare
  control.regReadB := DontCare
  control.regReadC := DontCare
  control.regWriteIndex := DontCare
  control.regWriteEnable := false.B
  control.updateConditionCodes := false.B
  control.busB := DontCare
  control.immediate := DontCare
  control.aluOpcode := DontCare
  control.shiftKind := ShiftKind.LogicalShiftLeft
  control.shiftImmediate := 0.U
  control.shiftDoLatch := false.B
  control.shiftUseLatched := false.B
  control.memWrite := false.B
  control.memWidth := DontCare
  control.memTransaction := BusTransactionType.Internal
  control.memProt.privileged := false.B // TODO
  control.memProt.data := false.B
  control.latchMemReadData := false.B
  control.latchMemWriteData := false.B
  control.memReadDataSigned := DontCare

  printf(cf"Execute [${instruction.condition} -> ${execute}] ${instruction.kind} ${stage}\n")
  when (execute) {
    switch (instruction.kind) {
      is (InstructionKind.Undefined) {
        printf("Undefined instruction\n")
        // TODO interrupt
        nextInstruction()
      }
      is (InstructionKind.DataProcessingImm) {
        // Rd := Alu(Rn, Imm)
        control.regReadA := instruction.regN
        control.aluOpcode := instruction.opcode.asTypeOf(AluOpcode())
        control.shiftKind := ShiftKind.RotateRight
        control.shiftImmediate := instruction.immediate(11, 8) << 1
        control.immediate := instruction.immediate(7, 0)
        control.busB := BusBValue.Immediate
        control.regWriteIndex := instruction.regD
        control.regWriteEnable := true.B
        control.updateConditionCodes := instruction.flags(0)
        when (instruction.regD === 15.U) {
          branch()
        } .otherwise {
          nextInstruction()
        }
      }
      is (InstructionKind.DataProcessingImmShift) {
        // Rd := Alu(Rn, Rm shift Imm)
        val shiftImmediate = instruction.immediate(6, 2)
        val shiftKind = suppressEnumCastWarning { instruction.immediate(1, 0).asTypeOf(ShiftKind()) }
        control.regReadA := instruction.regN
        control.regReadB := instruction.regM
        control.aluOpcode := instruction.opcode.asTypeOf(AluOpcode())
        control.shiftKind := shiftKind
        control.shiftImmediate := shiftImmediate
        when (shiftImmediate === 0.U) {
          switch(shiftKind) {
            // Right shift [both] of 0 is actually shift of 32
            is (ShiftKind.LogicalShiftRight, ShiftKind.ArithmeticShiftRight) {
              control.shiftImmediate := 32.U
            }
            // Rotate right of 0 is actually rotate right with extend
            is (ShiftKind.RotateRight) {
                control.shiftKind := ShiftKind.RotateRightWithExtend
            }
          }
        }
        control.busB := BusBValue.RegisterB
        control.regWriteIndex := instruction.regD
        control.regWriteEnable := true.B
        control.updateConditionCodes := instruction.flags(0)
        when (instruction.regD === 15.U) {
          branch()
        } .otherwise {
          nextInstruction()
        }
      }
      is (InstructionKind.DataProcessingRegShift) {
        switch (stage) {
          is (0.U) {
            control.regReadB := instruction.regS
            control.shiftDoLatch := true.B
            beginPrefetch()
            advanceStage()
          }
          is (1.U) {
            // Rd := Alu(Rn, Rm shift Imm)
            val shiftKind = suppressEnumCastWarning { instruction.immediate(1, 0).asTypeOf(ShiftKind()) }
            control.regReadA := instruction.regN
            control.regReadB := instruction.regM
            control.aluOpcode := instruction.opcode.asTypeOf(AluOpcode())
            control.shiftKind := shiftKind
            control.shiftUseLatched := true.B
            control.busB := BusBValue.RegisterB
            control.regWriteIndex := instruction.regD
            control.regWriteEnable := true.B
            control.updateConditionCodes := instruction.flags(0)
            when (instruction.regD === 15.U) {
              branch()
            } .otherwise {
              completePrefetch()
            }
          }
        }
      }
      is (InstructionKind.Load) {
        val width = suppressEnumCastWarning { instruction.opcode(1, 0).asTypeOf(BusAccessWidth()) }
        val flag_user = instruction.flags(5) // TODO
        val flag_signed = instruction.flags(4)
        val flag_immediate = instruction.flags(3)
        val flag_preindex = instruction.flags(2)
        val flag_add = instruction.flags(1)
        val flag_writeback = instruction.flags(0)

        switch (stage) {
          is (0.U) {
            // Calculate address, initiate access
            when (flag_preindex) {
              setAluLoadStoreAddress()
            } .otherwise {
              control.regReadB := instruction.regN
              control.busB := BusBValue.RegisterB
              control.aluOpcode := AluOpcode.mov
            }
            control.addressSource := AddressSource.Alu

            control.memTransaction := BusTransactionType.NonSequential
            control.memWrite := false.B
            control.memWidth := width
            control.memProt.data := true.B
            control.pcNext := PcNext.Incrementer
            advanceStage()
          }
          is (1.U) {
            // Wait for access, perform address writeback
            when (flag_writeback) {
              setAluLoadStoreAddress()
              control.regWriteIndex := instruction.regN
              control.regWriteEnable := true.B
            }

            control.latchMemReadData := true.B
            beginPrefetch()
            control.addressSource := AddressSource.Pc
            control.pcNext := PcNext.Same
            advanceStage()
          }
          is (2.U) {
            // Write the loaded data to the register.
            control.busB := BusBValue.MemReadData
            control.memReadDataSigned := flag_signed
            control.aluOpcode := AluOpcode.mov
            control.shiftKind := Mux(flag_signed, ShiftKind.ArithmeticShiftRight, ShiftKind.RotateRight)
            control.regWriteIndex := instruction.regD
            control.regWriteEnable := true.B
            when (instruction.regD === 15.U) {
              branch()
            } .otherwise {
              completePrefetch()
            }
          }
        }
      }
      is (InstructionKind.Store) {
        val width = suppressEnumCastWarning { instruction.opcode(1, 0).asTypeOf(BusAccessWidth()) }
        val flag_user = instruction.flags(5) // TODO
        val flag_immediate = instruction.flags(3)
        val flag_preindex = instruction.flags(2)
        val flag_add = instruction.flags(1)
        val flag_writeback = instruction.flags(0)

        switch (stage) {
          is (0.U) {
            // Calculate address, initiate access
            // XXX: if base addr regN is the same as store regD, the stored data is *pre* writeback
            when (flag_preindex) {
              setAluLoadStoreAddress()
            } .otherwise {
              control.regReadB := instruction.regN
              control.busB := BusBValue.RegisterB
              control.aluOpcode := AluOpcode.mov
            }
            control.addressSource := AddressSource.Alu

            // XXX: is there a way to do this without adding a third register read port?
            control.regReadC := instruction.regD
            control.latchMemWriteData := true.B

            control.memTransaction := BusTransactionType.NonSequential
            control.memWrite := true.B
            control.memWidth := width
            control.memProt.data := true.B
            control.pcNext := PcNext.Incrementer
            advanceStage()
          }
          is (1.U) {
            // Base modification
            when (flag_writeback) {
              setAluLoadStoreAddress()
              control.regWriteIndex := instruction.regN
              control.regWriteEnable := true.B
            }

            nextInstruction()
            control.memTransaction := BusTransactionType.NonSequential
            control.pcNext := PcNext.Same
            control.addressSource := AddressSource.Pc
          }
        }
      }
    }
  } .otherwise {
    // Unexecuted instruction
    nextInstruction()
  }

  // Setup the ALU to calculate the offset address for a load/store instruction.
  private def setAluLoadStoreAddress(): Unit = {
    val flag_immediate = instruction.flags(3)
    val flag_add = instruction.flags(1)

    // Calculate address, initiate access
    when (flag_immediate) {
      control.immediate := instruction.immediate
      control.busB := BusBValue.Immediate
    } .otherwise {
      control.regReadB := instruction.regM
      control.busB := BusBValue.RegisterB
      val shiftImmediate = instruction.immediate(6, 2)
      val shiftKind = suppressEnumCastWarning { instruction.immediate(1, 0).asTypeOf(ShiftKind()) }
      control.shiftImmediate := shiftImmediate
      control.shiftKind := shiftKind
    }
    control.regReadA := instruction.regN
    control.aluOpcode := Mux(flag_add, AluOpcode.add, AluOpcode.sub)
  }


  private def beginPrefetch(): Unit = {
    control.pcNext := PcNext.Incrementer
    control.addressSource := AddressSource.Incrementer
    control.memWrite := false.B
    control.memWidth := BusAccessWidth.Word // todo thumb
    control.memTransaction := BusTransactionType.Internal
  }

  private def continuePrefetch(): Unit = {
    // TODO: multi-cycle I-I-I-I-S (middle I), like in a multiply
  }

  /// Complete the prefetch of a merged I-S cycle, and go to the next instruction
  private def completePrefetch(): Unit = {
    control.memWrite := false.B
    control.memWidth := BusAccessWidth.Word // todo thumb
    control.memTransaction := BusTransactionType.Sequential
    control.nextInstruction := true.B
  }

  private def nextInstruction(): Unit = {
    control.pcNext := PcNext.Incrementer
    control.addressSource := AddressSource.Incrementer
    control.memWrite := false.B
    control.memWidth := BusAccessWidth.Word // todo thumb
    control.memTransaction := BusTransactionType.Sequential
    control.nextInstruction := true.B
  }

  /// After modifiying PC, flush pipeline.
  private def branch(): Unit = {
    // TODO
    control.pcNext := PcNext.Same
    control.addressSource := AddressSource.Alu
    control.branch := true.B
    control.nextInstruction := true.B
    control.memWrite := false.B
    control.memWidth := BusAccessWidth.Word // todo thumb
    control.memTransaction := BusTransactionType.NonSequential
  }

  private def advanceStage(): Unit = {
    nextStage := stage + 1.U
  }
}

object Control {
  private def evaluateCondition(condition: Condition.Type, flags: ConditionFlags): Bool = {
    MuxLookup(condition, false.B)(Seq(
      Condition.Eq -> flags.z,
      Condition.Ne -> !flags.z,
      Condition.Cs -> flags.c,
      Condition.Cc -> !flags.c,
      Condition.Mi -> flags.n,
      Condition.Pl -> !flags.n,
      Condition.Vs -> flags.v,
      Condition.Vc -> !flags.v,
      Condition.Hi -> (flags.c && !flags.z),
      Condition.Ls -> (!flags.c || flags.z),
      Condition.Ge -> !(flags.n ^ flags.v),
      Condition.Lt -> (flags.n ^ flags.v),
      Condition.Gt -> (!flags.z && !(flags.n ^ flags.v)),
      Condition.Le -> (flags.z && (flags.n ^ flags.v)),
      Condition.Al -> true.B,
      Condition.Nv -> false.B,
    ))
  }
}