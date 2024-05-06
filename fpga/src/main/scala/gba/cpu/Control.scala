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
  val Immediate = Value
}

object BusBValue extends ChiselEnum {
  val RegisterB = Value
  val Immediate = Value
  val MemReadData = Value
  val Cpsr = Value
  val Spsr = Value
}

object ExceptionKind extends ChiselEnum {
  val None = Value
  val Reset = Value
  val UndefinedInstruction = Value
  val SoftwareInterrupt = Value
  val PrefetchAbort = Value
  val DataAbort = Value
  val Irq = Value
  val Fiq = Value
}

class ControlSignals extends Bundle {
  /// True to start advance the fetch/decode stages of the pipeline.
  val advancePipeline = Bool()
  val flushPipeline = Bool()
  val startException = Bool()

  val pcNext = PcNext()
  val addressSource = AddressSource()

  val regBankMode = CpuMode()
  val regReadA = UInt(4.W)
  val regReadB = UInt(4.W)
  val regReadC = UInt(4.W)
  val regWriteIndex = UInt(4.W)
  val regWriteEnable = Bool()
  val cpsrUpdateCond = Bool()
  val cpsrUpdateThumb = Bool()
  val cpsrUpdateFields = UInt(2.W)
  val spsrUpdateFields = UInt(2.W)
  val cpsrRestore = Bool()

  val busB = BusBValue()
  val immediate = UInt(32.W)

  val aluOpcode = AluOpcode()
  val shiftKind = ShiftKind()
  val shiftImmediate = UInt(6.W)
  val shiftDoLatch = Bool()
  val shiftUseLatched = Bool()

  val memTransaction = BusTransactionType()
  val memWrite = Bool()
  val memWidth = BusAccessWidth()
  val memProt = new BusProtectionType
  val memLock = Bool()
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
    /// Next program status register
    val nextStatus = Input(new ProgramStatusRegister)

    /// Active-high fast interrupt request
    val fiq = Input(Bool())
    /// Active-high interrupt request
    val irq = Input(Bool())
  })
  val control = io.signals

  val doReset = RegInit(true.B)
  val instruction = RegInit((new DecodedInstruction).Lit(
    _.condition -> Condition.Nv
  ))
  val stage = RegInit(0.U(3.W))
  val nextStage = WireDefault(stage)
  val counter = Reg(UInt(5.W)) // Counter used for LDM/STM
  val nextCounter = WireDefault(counter)
  val dispatch = WireDefault(false.B)
  when (io.enable) {
    stage := nextStage
    counter := nextCounter
    when (dispatch) {
      stage := 0.U
      when (doReset) {
        doReset := false.B
        instruction.kind := InstructionKind.Exception
        instruction.opcode := ExceptionKind.Reset.asUInt
        instruction.condition := Condition.Al
      } .elsewhen (io.fiq && !io.currentStatus.fiqDisable) {
        instruction.kind := InstructionKind.Exception
        instruction.opcode := ExceptionKind.Fiq.asUInt
        instruction.condition := Condition.Al
      } .elsewhen (io.irq && !io.currentStatus.irqDisable) {
        instruction.kind := InstructionKind.Exception
        instruction.opcode := ExceptionKind.Irq.asUInt
        instruction.condition := Condition.Al
      } .otherwise {
        instruction := io.nextInstruction
      }
    }
  }
  val execute = Control.evaluateCondition(instruction.condition, io.currentStatus.cond)
  val nextThumb = io.nextStatus.thumb

  control.advancePipeline := false.B
  control.flushPipeline := false.B
  control.startException := false.B
  control.pcNext := PcNext.Same
  control.addressSource := AddressSource.Same
  control.regBankMode := io.currentStatus.mode
  control.regReadA := DontCare
  control.regReadB := DontCare
  control.regReadC := DontCare
  control.regWriteIndex := DontCare
  control.regWriteEnable := false.B
  control.cpsrUpdateCond := false.B
  control.cpsrUpdateThumb := false.B
  control.cpsrUpdateFields := 0.U
  control.spsrUpdateFields := 0.U
  control.cpsrRestore := false.B
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
  control.memLock := false.B
  control.latchMemReadData := false.B
  control.latchMemWriteData := false.B
  control.memReadDataSigned := false.B

  printf(cf"Execute [${instruction.condition} -> ${execute}] ${instruction.kind} ${stage}\n")
  when (execute) {
    switch (instruction.kind) {
      is (InstructionKind.Exception) {
        val kind = suppressEnumCastWarning { instruction.opcode(2, 0).asTypeOf(ExceptionKind()) }
        val newAddress = MuxLookup(kind, 0.U)(Seq(
          ExceptionKind.Reset -> 0.U,
          ExceptionKind.UndefinedInstruction -> 0x4.U,
          ExceptionKind.SoftwareInterrupt -> 0x8.U,
          ExceptionKind.PrefetchAbort -> 0xC.U,
          ExceptionKind.DataAbort -> 0x10.U,
          ExceptionKind.Irq -> 0x18.U,
          ExceptionKind.Fiq -> 0x1C.U,
        ))
        val newMode = MuxLookup(kind, CpuMode.Supervisor)(Seq(
          ExceptionKind.Reset -> CpuMode.Supervisor,
          ExceptionKind.UndefinedInstruction -> CpuMode.Undefined,
          ExceptionKind.SoftwareInterrupt -> CpuMode.Supervisor,
          ExceptionKind.PrefetchAbort -> CpuMode.Abort,
          ExceptionKind.DataAbort -> CpuMode.Abort,
          ExceptionKind.Irq -> CpuMode.Irq,
          ExceptionKind.Fiq -> CpuMode.Fiq,
        ))
        val entryThumb = Reg(Bool())

        switch (stage) {
          is (0.U) {
            printf(cf"Exception! ${kind}\n")
            flushPipeline()
            dispatch := false.B
            entryThumb := io.currentStatus.thumb

            // Construct forced address
            control.immediate := newAddress
            control.addressSource := AddressSource.Immediate

            // Change mode, set ARM mode, set I high.
            // In Reset and Fiq, set F high too.
            // Move CPSR -> (new) SPSR
            control.regBankMode := newMode
            control.startException := true.B

            // Move PC -> (new) LR
            control.regReadB := 15.U
            control.busB := BusBValue.RegisterB
            control.aluOpcode := AluOpcode.mov
            control.regWriteIndex := 14.U
            control.regWriteEnable := true.B

            advanceStage()
          }
          is (1.U) {
            // Modify return address (to facilitate return):
            // r14 is currently set to (next instruction to be executed + 2i)
            // IRQ: set it to next instruction + 4  (-2i + 4)
            // SWI/undef: set it to next instruction after SWI (+ i)
            control.aluOpcode := AluOpcode.sub
            control.regReadA := 14.U
            when (kind === ExceptionKind.SoftwareInterrupt || kind === ExceptionKind.UndefinedInstruction) {
              control.immediate := Mux(entryThumb, 2.U, 4.U)
            } .otherwise {
              control.immediate := Mux(entryThumb, 0.U, 4.U)
            }
            control.busB := BusBValue.Immediate
            control.regWriteIndex := 14.U
            control.regWriteEnable := true.B

            nextInstruction()
            dispatch := false.B
            advanceStage()
          }
          is (2.U) {
            // Refill instruction pipeline.
            nextInstruction()
          }
        }
      }
      is (InstructionKind.DataProcessingImm) {
        // Rd := Alu(Rn, Imm)
        control.shiftKind := ShiftKind.RotateRight
        control.shiftImmediate := instruction.immediate(11, 8) << 1
        control.immediate := instruction.immediate(7, 0)
        control.busB := BusBValue.Immediate
        finishDataProcessing()
      }
      is (InstructionKind.DataProcessingImmShift) {
        // Rd := Alu(Rn, Rm shift Imm)
        val shiftImmediate = instruction.immediate(6, 2)
        val shiftKind = suppressEnumCastWarning { instruction.immediate(1, 0).asTypeOf(ShiftKind()) }
        control.regReadB := instruction.regM
        control.busB := BusBValue.RegisterB
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
        finishDataProcessing()
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
            control.regReadB := instruction.regM
            control.busB := BusBValue.RegisterB
            control.shiftKind := shiftKind
            control.shiftUseLatched := true.B
            finishDataProcessing(didPrefetch = true)
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
              flushPipeline()
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
            // Note: if base addr regN is the same as store regD, the stored data is *pre* writeback
            when (flag_preindex) {
              setAluLoadStoreAddress()
            } .otherwise {
              control.regReadB := instruction.regN
              control.busB := BusBValue.RegisterB
              control.aluOpcode := AluOpcode.mov
            }
            control.addressSource := AddressSource.Alu
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

            // XXX: is there a way to do this without adding a third register read port?
            control.regReadC := instruction.regD

            nextInstruction()
            control.memTransaction := BusTransactionType.NonSequential
            control.pcNext := PcNext.Same
            control.addressSource := AddressSource.Pc
          }
        }
      }
      is (InstructionKind.Swap) {
        val width = suppressEnumCastWarning { instruction.opcode(1, 0).asTypeOf(BusAccessWidth()) }

        switch (stage) {
          is (0.U) {
            // Start load from Rn
            control.regReadB := instruction.regN
            control.busB := BusBValue.RegisterB
            control.aluOpcode := AluOpcode.mov
            control.addressSource := AddressSource.Alu
            control.memTransaction := BusTransactionType.NonSequential
            control.memWrite := false.B
            control.memWidth := width
            control.memProt.data := true.B
            control.pcNext := PcNext.Incrementer
            advanceStage()
          }
          is (1.U) {
            // Latch loaded data, start store to Rn (with Rm)
            control.latchMemReadData := true.B
            // XXX: this *could* go over bus B
            control.regReadC := instruction.regM
            control.latchMemWriteData := true.B

            control.addressSource := AddressSource.Same
            control.memTransaction := BusTransactionType.NonSequential
            control.memWrite := true.B
            control.memWidth := width
            control.memProt.data := true.B
            control.memLock := true.B
            advanceStage()
          }
          is (2.U) {
            // Wait for the store... start merged I-S cycle
            beginPrefetch()
            control.addressSource := AddressSource.Pc
            control.pcNext := PcNext.Same
            control.memLock := true.B
            advanceStage()
          }
          is (3.U) {
            // Write the loaded data to the register.
            control.busB := BusBValue.MemReadData
            control.aluOpcode := AluOpcode.mov
            control.shiftKind := ShiftKind.RotateRight
            control.regWriteIndex := instruction.regD
            control.regWriteEnable := true.B
            completePrefetch()
          }
        }
      }
      is (InstructionKind.ArmBranch) {
        val flag_link = instruction.flags(0)
        val flag_exchange = instruction.flags(1)

        switch (stage) {
          is (0.U) {
            when (flag_exchange) {
              control.regReadA := instruction.regM
              control.busB := BusBValue.Immediate
              control.immediate := 1.U
              control.aluOpcode := AluOpcode.bic
              control.cpsrUpdateThumb := true.B
            } .otherwise {
              control.regReadA := 15.U // PC
              control.busB := BusBValue.Immediate
              control.immediate := Cat(
                Fill(6, instruction.immediate(23)),
                instruction.immediate(23, 0),
                "b00".U(2.W)
              )
              control.aluOpcode := AluOpcode.add
            }
            flushPipeline()
            dispatch := false.B
            advanceStage()
          }
          is (1.U) {
            when (flag_link) {
              // If link, save LR := PC - 4 (to point to the instruction after the branch)
              // Note: this is always executed from ARM mode, so it's always 4.
              control.regWriteEnable := true.B
              control.regWriteIndex := 14.U // LR
              control.regReadA := 15.U // PC
              control.busB := BusBValue.Immediate
              control.immediate := 4.U
              control.aluOpcode := AluOpcode.sub
            }
            nextInstruction()
            dispatch := false.B
            advanceStage()
          }
          is (2.U) {
            // And update the PC.
            nextInstruction()
          }
        }
      }
      is (InstructionKind.MoveFromStatusRegister) {
        val flag_spsr = instruction.flags(0)
        control.busB := Mux(flag_spsr, BusBValue.Spsr, BusBValue.Cpsr)
        control.aluOpcode := AluOpcode.mov
        control.regWriteIndex := instruction.regD
        control.regWriteEnable := true.B
        nextInstruction()
        // XXX: if target is R15, is the pipeline flushed or not?
      }
      is (InstructionKind.MoveToStatusRegister) {
        val flag_spsr = instruction.flags(0)
        val flag_immediate = instruction.flags(1)
        when (flag_immediate) {
          control.shiftKind := ShiftKind.RotateRight
          control.shiftImmediate := instruction.immediate(11, 8) << 1
          control.immediate := instruction.immediate(7, 0)
          control.busB := BusBValue.Immediate
        } .otherwise {
          control.busB := BusBValue.RegisterB
          control.regReadB := instruction.regM
        }
        control.aluOpcode := AluOpcode.mov
        when (flag_spsr) {
          control.spsrUpdateFields := Cat(instruction.opcode(3), instruction.opcode(0))
        } .otherwise {
          control.cpsrUpdateFields := Cat(instruction.opcode(3), instruction.opcode(0))
        }
        nextInstruction()
      }
      is (InstructionKind.LoadMultiple) {
        val flag_writeback = instruction.flags(0)
        val flag_s = instruction.flags(1)
        val flag_up = instruction.flags(2)
        val flag_preindex = instruction.flags(3)
        // TODO: works differently with 'S' flag (user registers, CPSR restore, etc.)

        // Special handling for empty list: transfer R15 only, but increment/decrement base by full 64 bytes.
        val regList = instruction.immediate(15, 0)
        val regListEmpty = regList === 0.U
        val regCount = PopCount(regList)
        val regNextIndex = Mux(regListEmpty, 15.U, PriorityEncoder(regList))

        when (stage === 0.U) {
          // Calculate start address
          // Note: address is force aligned, which is fine: memory system will align,
          // and we don't rotate upon read.
          control.regReadA := instruction.regN
          control.immediate := Mux(flag_up,
            flag_preindex,
            Mux(regListEmpty, 16.U, regCount) - (!flag_preindex).asUInt
          )
          control.busB := BusBValue.Immediate
          control.aluOpcode := Mux(flag_up, AluOpcode.add, AluOpcode.sub)
          control.shiftKind := ShiftKind.LogicalShiftLeft
          control.shiftImmediate := 2.U
          control.addressSource := AddressSource.Alu
          control.memTransaction := BusTransactionType.NonSequential
          control.memWrite := false.B
          control.memWidth := BusAccessWidth.Word
          control.memProt.data := true.B
          nextCounter := Mux(regListEmpty, 1.U, regCount) - 1.U
          control.pcNext := PcNext.Incrementer
          advanceStage()
        }

        when (stage === 1.U) {
          // Update base (if writeback)
          control.regReadA := instruction.regN
          control.regWriteEnable := flag_writeback
          control.regWriteIndex := instruction.regN
          control.immediate := Mux(regListEmpty, 16.U, regCount)
          control.busB := BusBValue.Immediate
          control.aluOpcode := Mux(flag_up, AluOpcode.add, AluOpcode.sub)
          control.shiftKind := ShiftKind.LogicalShiftLeft
          control.shiftImmediate := 2.U
          control.addressSource := AddressSource.Alu

          advanceStage()
        }

        when (stage === 1.U || stage === 2.U) {
          // Sequential memory accesses after the first
          control.addressSource := AddressSource.Incrementer
          control.memTransaction := BusTransactionType.Sequential
          control.memWrite := false.B
          control.memWidth := BusAccessWidth.Word
          control.memProt.data := true.B
          control.latchMemReadData := true.B
          nextCounter := counter - 1.U
          when (counter === 0.U) {
            // Begin I-S prefetch cycle
            beginPrefetch()
            control.addressSource := AddressSource.Pc
            control.pcNext := PcNext.Same
            // Skip stage 2 for single register load
            advanceStage(Mux(regCount > 1.U && !regListEmpty, 1.U, 2.U))
          }
        }

        when (stage === 2.U && io.enable) {
          // Unset the next bit (unless we're on the last cycle, to not corrupt next instruction).
          instruction.immediate := regList & (~(1.U << regNextIndex)).asUInt
        }

        when (stage >= 2.U) {
          // Write loaded RDATA to the next register in the list.
          control.busB := BusBValue.MemReadData
          control.aluOpcode := AluOpcode.mov
          control.regWriteIndex := regNextIndex
          control.regWriteEnable := true.B
        }

        when (stage === 3.U) {
          // Complete fetch, next cycle
          when (control.regWriteIndex === 15.U) {
            // If writing PC, flush the pipeline
            flushPipeline()
          } .otherwise {
            completePrefetch()
          }
        }
      }
      is (InstructionKind.StoreMultiple) {
        val flag_writeback = instruction.flags(0)
        val flag_s = instruction.flags(1)
        val flag_up = instruction.flags(2)
        val flag_preindex = instruction.flags(3)
        // TODO: works differently with 'S' flag (user registers, CPSR restore, etc.)

        // Special handling for empty list: transfer R15 only, but increment/decrement base by full 64 bytes.
        val regList = instruction.immediate(15, 0)
        val regListEmpty = regList === 0.U
        val regCount = PopCount(regList)
        val regNextIndex = Mux(regListEmpty, 15.U, PriorityEncoder(regList))

        when (stage === 0.U) {
          // Calculate start address
          control.regReadA := instruction.regN
          control.immediate := Mux(flag_up,
            flag_preindex,
            Mux(regListEmpty, 16.U, regCount) - (!flag_preindex).asUInt
          )
          control.busB := BusBValue.Immediate
          control.aluOpcode := Mux(flag_up, AluOpcode.add, AluOpcode.sub)
          control.shiftKind := ShiftKind.LogicalShiftLeft
          control.shiftImmediate := 2.U
          control.addressSource := AddressSource.Alu
          control.memTransaction := BusTransactionType.NonSequential
          control.memWrite := true.B
          control.memWidth := BusAccessWidth.Word
          control.memProt.data := true.B
          nextCounter := Mux(regListEmpty, 1.U, regCount) - 1.U
          control.pcNext := PcNext.Incrementer
          advanceStage()
        }

        when (stage === 1.U) {
          // Update base (if writeback)
          control.regReadA := instruction.regN
          control.regWriteEnable := flag_writeback
          control.regWriteIndex := instruction.regN
          control.immediate := Mux(regListEmpty, 16.U, regCount)
          control.busB := BusBValue.Immediate
          control.aluOpcode := Mux(flag_up, AluOpcode.add, AluOpcode.sub)
          control.shiftKind := ShiftKind.LogicalShiftLeft
          control.shiftImmediate := 2.U
          advanceStage()
        }

        when (stage >= 1.U) {
          // Store registers
          control.addressSource := AddressSource.Incrementer
          control.memTransaction := BusTransactionType.Sequential
          control.memWrite := true.B
          control.memWidth := BusAccessWidth.Word
          control.memProt.data := true.B
          control.regReadC := regNextIndex

          nextCounter := counter - 1.U
          when (counter === 0.U) {
            // Begin next instruction fetch
            nextInstruction()
            control.memTransaction := BusTransactionType.NonSequential
            control.pcNext := PcNext.Same
            control.addressSource := AddressSource.Pc
          } .elsewhen (io.enable) {
            // Unset the next bit (unless we're on the last cycle, to not corrupt next instruction).
            instruction.immediate := regList & (~(1.U << regNextIndex)).asUInt
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
      control.shiftKind := shiftKind
      control.shiftImmediate := shiftImmediate
      when (shiftImmediate === 0.U) {
        switch (shiftKind) {
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
    }
    control.regReadA := instruction.regN
    control.aluOpcode := Mux(flag_add, AluOpcode.add, AluOpcode.sub)
  }

  // Complete a data processing instruction
  private def finishDataProcessing(didPrefetch: Boolean = false): Unit = {
    val testOnly = instruction.opcode(3, 2) === "b10".U(2.W)

    control.regReadA := instruction.regN
    control.aluOpcode := instruction.opcode.asTypeOf(AluOpcode())
    control.regWriteIndex := instruction.regD
    control.regWriteEnable := !testOnly
    control.cpsrUpdateCond := instruction.flags(0)

    when (instruction.regD === 15.U && control.cpsrUpdateCond) {
      // 'S' instructions restore (CPSR := SPSR) when Rd = PC
      control.cpsrRestore := true.B
    }
    
    when (instruction.regD === 15.U && !testOnly) {
      flushPipeline()
    } .otherwise {
      if (didPrefetch) {
        completePrefetch()
      } else {
        nextInstruction()
      }
    }
  }

  private def beginPrefetch(): Unit = {
    control.pcNext := PcNext.Incrementer
    control.addressSource := AddressSource.Incrementer
    control.memWrite := false.B
    control.memWidth := Mux(nextThumb, BusAccessWidth.Halfword, BusAccessWidth.Word)
    control.memTransaction := BusTransactionType.Internal
  }

  private def continuePrefetch(): Unit = {
    // TODO: multi-cycle I-I-I-I-S (middle I), like in a multiply
  }

  /// Complete the prefetch of a merged I-S cycle, and go to the next instruction
  private def completePrefetch(): Unit = {
    control.memWrite := false.B
    control.memWidth := Mux(nextThumb, BusAccessWidth.Halfword, BusAccessWidth.Word)
    control.memTransaction := BusTransactionType.Sequential
    control.advancePipeline := true.B
    dispatch := true.B
  }

  private def nextInstruction(): Unit = {
    control.pcNext := PcNext.Incrementer
    control.addressSource := AddressSource.Incrementer
    control.memWrite := false.B
    control.memWidth := Mux(nextThumb, BusAccessWidth.Halfword, BusAccessWidth.Word)
    control.memTransaction := BusTransactionType.Sequential
    control.advancePipeline := true.B
    dispatch := true.B
  }

  /// After modifying PC, flush pipeline.
  private def flushPipeline(): Unit = {
    control.pcNext := PcNext.Same
    control.addressSource := AddressSource.Alu
    control.flushPipeline := true.B
    control.advancePipeline := true.B
    control.memWrite := false.B
    control.memWidth := Mux(nextThumb, BusAccessWidth.Halfword, BusAccessWidth.Word)
    control.memTransaction := BusTransactionType.NonSequential
    dispatch := true.B
  }

  private def advanceStage(by: UInt = 1.U): Unit = {
    nextStage := stage + by
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
      Condition.Le -> (flags.z || (flags.n ^ flags.v)),
      Condition.Al -> true.B,
      Condition.Nv -> false.B,
    ))
  }
}