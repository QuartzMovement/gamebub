package gba.cpu

import chisel3._
import chisel3.util._

object Condition extends ChiselEnum {
  /// Equal, Z = 1
  val Eq = Value
  /// Not equal, Z = 0
  val Ne = Value
  /// Carry set, C = 1
  val Cs = Value
  /// Carry clear, C = 0
  val Cc = Value
  /// Minus, N = 1
  val Mi = Value
  /// Plus, N = 0
  val Pl = Value
  /// Overflow, V = 1
  val Vs = Value
  /// No overflow, V = 0
  val Vc = Value
  /// Unsigned higher, C = 1 and Z = 0
  val Hi = Value
  /// Unsigned lower or same, C = 0 or Z = 1
  val Ls = Value
  /// Signed greater than or equal, N = V
  val Ge = Value
  /// Signed less than, N != V
  val Lt = Value
  /// Signed greater than, Z = 0, N = V
  val Gt = Value
  /// Signed less than or equal, Z = 1, N != V
  val Le = Value
  /// Always
  val Al = Value
  /// Never
  val Nv = Value
}

object InstructionKind extends ChiselEnum {
  val Exception = Value
  val DataProcessingImm = Value
  val DataProcessingImmShift = Value
  val DataProcessingRegShift = Value
  val Load = Value
  val Store = Value
  val Swap = Value
  val ArmBranch = Value
  val MoveFromStatusRegister = Value
  val MoveToStatusRegister = Value
  val LoadMultiple = Value
  val StoreMultiple = Value
  val Multiply = Value
}

class DecodedInstruction extends Bundle {
  val kind = InstructionKind()
  /// Condition code
  val condition = Condition()
  /// Rn (or Rd_low)
  val regN = UInt(4.W)
  /// Rd (or Rd_high)
  val regD = UInt(4.W)
  /// Rs
  val regS = UInt(4.W)
  /// Rm
  val regM = UInt(4.W)
  /// Immediate / offset (may be multiple fields)
  ///   e.g. shift immediate, register list, b/bl
  val immediate = UInt(24.W)
  /// Per-instruction opcode
  val opcode = UInt(4.W)
  /// Per-instruction flags
  val flags = UInt(6.W)
}

/// Instruction fetch and decode
class Decoder extends Module {
  val io = IO(new Bundle {
    /// Global enable
    val enable = Input(Bool())

    /// Thumb mode
    val thumb = Input(Bool())

    /// Advance to next instruction
    val advancePipeline = Input(Bool())
    /// Flush the pipeline
    val flushPipeline = Input(Bool())

    /// Memory read data
    val readData = Input(UInt(32.W))
    /// Memory read address (alignment)
    val readAddress = Input(UInt(2.W))

    /// Decoded instruction
    val decoded = Output(new DecodedInstruction)
  })

  // TODO handle CLOCKEN (bus cycle stretching)
  // Fetch stage, with support for latching the first read value
  // during multi-cycle instructions.
  val currentFetch = Mux(io.thumb,
    Mux(io.readAddress(1), io.readData(31, 16), io.readData(15, 0)),
    io.readData
  )
  val isNewFetch = RegInit(true.B)
  when (io.enable) {
    isNewFetch := io.advancePipeline
  }
  val fetchRegValid = RegInit(false.B)
  val fetchReg = Reg(UInt(32.W))
  when (io.enable && (!io.advancePipeline && isNewFetch)) {
    fetchReg := currentFetch
    fetchRegValid := true.B
  }
  val fetchResult = Mux(isNewFetch, currentFetch, fetchReg)
  val fetchResultValid = Mux(isNewFetch, true.B, fetchRegValid)

  // Decode stage.
  val decodeReg = Reg(UInt(32.W))
  val decodeRegValid = RegInit(false.B)
  when (io.enable && io.advancePipeline) {
    decodeReg := fetchResult
    decodeRegValid := fetchResultValid
  }
  val in = decodeReg
//  printf(cf"decoding ${in}%x, fetching ${fetchResult}%x\n")

  when (io.enable && io.flushPipeline) {
    fetchRegValid := false.B
    decodeRegValid := false.B
  }

  val out = io.decoded
  out.kind := InstructionKind.Exception
  out.condition := Condition.Al
  out.regN := DontCare
  out.regD := DontCare
  out.regS := DontCare
  out.regM := DontCare
  out.immediate := DontCare
  out.opcode := ExceptionKind.UndefinedInstruction.asUInt
  out.flags := DontCare

  // Decode table
  when (io.flushPipeline || !decodeRegValid) {
    out.condition := Condition.Nv
  } .elsewhen (!io.thumb) {
    // ARM mode
    io.decoded.condition := in(31, 28).asTypeOf(Condition())

    when (in(27, 25) === "b000".U(3.W) && in(4) && in(7)) {
      // Multiply and additional loads/stores
      when (in(7, 4) === "b1001".U(4.W) && in(27, 23) === 0.U) {
        // Multiply [Accumulate]
        out.kind := InstructionKind.Multiply
        out.regM := in(3, 0)
        out.regS := in(11, 8)
        out.regN := in(15, 12)
        out.regD := in(19, 16)
        out.flags := Cat(0.U(2.W), in(21, 20)) // [Long, Signed, Accumulate, SetCond]
      } .elsewhen (in(7, 4) === "b1001".U(4.W) && in(27, 23) === 1.U) {
        // Multiple [Accumulate] Long
        out.kind := InstructionKind.Multiply
        out.regM := in(3, 0)
        out.regS := in(11, 8)
        out.regN := in(15, 12)
        out.regD := in(19, 16)
        out.flags := Cat(1.U(1.W), in(22, 20)) // [Long, Signed, Accumulate, SetCond]
      } .elsewhen (in(7, 4) === "b1001".U(4.W) && in(27, 23) === 2.U) {
        out.kind := InstructionKind.Swap
        out.opcode := Mux(in(22), BusAccessWidth.Byte, BusAccessWidth.Word).asUInt
        out.regN := in(19, 16)
        out.regD := in(15, 12)
        out.regM := in(3, 0)
      } .otherwise {
        // Load/store halfword / byte
        out.kind := Mux(in(20), InstructionKind.Load, InstructionKind.Store)
        out.opcode := Mux(in(5), BusAccessWidth.Halfword, BusAccessWidth.Byte).asUInt
        val writeback = !in(24) || in(21)  // (P == 0) || (W == 1)
        // if P == 0 and W == 1 -> unpredictable (??)
        out.flags := Cat(0.U(1.W), in(6), in(22), in(24), in(23), writeback)
        out.regN := in(19, 16)
        out.regD := in(15, 12)
        when (in(22)) {
          // [immediate (8)]
          out.immediate := Cat(in(11, 8), in(3, 0))
        } .otherwise {
          // LSL by 0 ([shift imm][shift type(2)]
          out.immediate := 0.U
        }
        out.regM := in(3, 0)
      }
    } .elsewhen (in(27, 26) === "b00".U(2.W) && !(in(24, 23) === "b10".U(2.W) && !in(20))) {
      // ALU data processing instructions
      when (in(25)) {
        // Immediate
        out.kind := InstructionKind.DataProcessingImm
        out.immediate := in(11, 0) // [rotate (4), immediate (8)]
      } .elsewhen (!in(4)) {
        // Immediate shift
        out.kind := InstructionKind.DataProcessingImmShift
        out.immediate := in(11, 5) // [shift imm (5), shift (2)]
        out.regM := in(3, 0)
      } .otherwise {
        // Register shift
        out.kind := InstructionKind.DataProcessingRegShift
        out.regS := in(11, 8)
        out.immediate := in(6, 5) // [shift (2)]
        out.regM := in(3, 0)
      }
      out.opcode := in(24, 21)
      out.flags := in(20) // [SetCond]
      out.regN := in(19, 16)
      out.regD := in(15, 12)
    } .elsewhen (in(27, 25) === "b000".U(3.W) && (in(24, 23) === "b10".U(2.W) && !in(20))) {
      // Miscellaneous functions
      when (in(7, 4) === "b0000".U) {
        when (in(21)) {
          // MSR: Move *to* status register (register operand)
          out.kind := InstructionKind.MoveToStatusRegister
          out.flags := Cat("b0".U(1.W), in(22)) // [Immediate, SPSR]
          out.opcode := in(19, 16) // Fields
          out.regM := in(3, 0)
        } .otherwise {
          // MRS: Move from status register
          out.kind := InstructionKind.MoveFromStatusRegister
          out.flags := in(22) // [SPSR]
          out.regD := in(15, 12)
        }
      } .elsewhen (in(7, 4) === "b0001".U(4.W) && in(22, 21) === "b01".U(2.W)) {
        out.kind := InstructionKind.ArmBranch
        out.flags := "b10".U(2.W) // [Exchange, Link]
        out.regM := in(3, 0)
      }
    } .elsewhen (in(27, 25) === "b001".U(3.W) && in(21, 20) === "b10".U(2.W) && in(24, 23) === "b10".U(2.W)) {
      // MSR: Move *to* status register (immediate operand)
      out.kind := InstructionKind.MoveToStatusRegister
      out.flags := Cat("b1".U(1.W), in(22)) // [Immediate, SPSR]
      out.opcode := in(19, 16) // Fields
      out.immediate := in(11, 0) // [rotate (4), immediate (8)]
    } .elsewhen(in(27, 25) === "b101".U(3.W)) {
      // Branch, Branch-and-link
      out.kind := InstructionKind.ArmBranch
      out.flags := Cat("b0".U(1.W), in(24)) // [Exchange, Link]
      out.immediate := in(23, 0)
    } .elsewhen (in(27, 26) === "b01".U(2.W)) {
      // Load and store word or unsigned byte.
      out.kind := Mux(in(20), InstructionKind.Load, InstructionKind.Store)
      out.opcode := Mux(in(22), BusAccessWidth.Byte, BusAccessWidth.Word).asUInt
      // flags: (user mode) (signed) (use immediate) (pre indexed) (*add* offset) (writeback to base)
      //        (TSIPUW)
      val userMode = !in(24) && in(21) // (P == 0) && (W == 1)
      val writeback = !in(24) || in(21) // (P == 0) || (W == 1)
      out.flags := Cat(userMode, 0.U(1.W), !in(25), in(24), in(23), writeback)
      out.regN := in(19, 16)
      out.regD := in(15, 12)
      out.immediate := Mux(in(25), in(11, 5), in(11, 0))
      // Immediate: [immediate (12)]
      //         OR [shift imm][shift type (2)]
    } .elsewhen(in(27, 25) === "b100".U(3.W)) {
      // Load/Store Multiple
      out.kind := Mux(in(20), InstructionKind.LoadMultiple, InstructionKind.StoreMultiple)
      out.regN := in(19, 16)
      out.flags := in(24, 21) // [P, U, S, W]
      out.immediate := in(15, 0)
    } .elsewhen(in(27, 24) === "b1111".U(4.W)) {
      out.kind := InstructionKind.Exception
      out.opcode := ExceptionKind.SoftwareInterrupt.asUInt
    }
  } .otherwise {
    // Thumb mode
    when (in(15, 13) === "b000".U(3.W)) {
      when (in(12, 11) =/= "b11".U(2.W)) {
        // TODO THUMB.1: shift by immediate
      } .otherwise {
        // TODO THUMB.2: add / subtract
      }
    } .elsewhen (in(15, 13) === "b001".U(3.W)) {
      // THUMB.3: move/compare/add/subtract immediate
      out.kind := InstructionKind.DataProcessingImm
      out.flags := 1.U // [SetCond]
      out.regD := in(10, 8)
      out.regN := in(10, 8)
      out.immediate := in(7, 0)
      out.opcode := VecInit(Seq(AluOpcode.mov, AluOpcode.cmp, AluOpcode.add, AluOpcode.sub))(in(12, 11)).asUInt
    } .elsewhen (in(15, 10) === "b010000".U(6.W)) {
      // TODO THUMB.4: data-processing register
    } .elsewhen (in(15, 10) === "b010001".U(6.W)) {
      // THUMB.5: Hi register operations/branch exchange
      val opcode = in(9, 8)
      out.regM := in(6, 3)
      out.regS := in(6, 3)
      out.regD := Cat(in(7), in(2, 0))
      out.regN := Cat(in(7), in(2, 0))
      when (opcode === 0.U) {
        out.kind := InstructionKind.DataProcessingImmShift
        out.opcode := AluOpcode.add.asUInt
        out.flags := 0.U // [SetCond]
        out.immediate := 0.U
      } .elsewhen (opcode === 1.U) {
        out.kind := InstructionKind.DataProcessingImmShift
        out.opcode := AluOpcode.cmp.asUInt
        out.flags := 1.U // [SetCond]
        out.immediate := 0.U
      } .elsewhen (opcode === 2.U) {
        out.kind := InstructionKind.DataProcessingImmShift
        out.opcode := AluOpcode.mov.asUInt
        out.flags := 0.U // [SetCond]
        out.immediate := 0.U
      } .otherwise {
        // Branch exchange
        out.kind := InstructionKind.ArmBranch
        out.flags := "b10".U(2.W) // [Exchange, Link]
      }
    } .elsewhen (in(15, 11) === "b01001".U(5.W)) {
      // TODO THUMB.6: load PC-relative
    } .elsewhen (in(15, 12) === "b0101".U(4.W)) {
      when (in(9) === 0.U) {
        // TODO THUMB.7: load/store with register offset
      } .otherwise {
        // TODO THUMB.8: load/store sign-extended byte/halfword
      }
    } .elsewhen (in(15, 13) === "b011".U(3.W)) {
      // TODO THUMB.9: load/store word/byte with immediate offset
    } .elsewhen (in(15, 10) === "b1000".U(4.W)) {
      // TODO THUMB.10: load/store halfword with immediate offset
    } .elsewhen (in(15, 12) === "b1001".U(4.W)) {
      // TODO THUMB.11: load/store SP relative
    } .elsewhen (in(15, 12) === "b1010".U(4.W)) {
      // THUMB.12: get relative address
      out.regD := in(10, 8)
      out.kind := InstructionKind.DataProcessingImm
      out.opcode := AluOpcode.add.asUInt
      out.immediate := in(7, 0) << 2
      when (in(11)) {
        // ADD  Rd,SP,#nn
        out.regN := 13.U
      } .otherwise {
        // ADD  Rd,PC,#nn
        // TODO: it's actually added with PC force-aligned to 4 (& 0xFFFFFFFC)
        out.regN := 15.U
      }
    } .elsewhen (in(15, 8) === "b10110000".U(8.W)) {
      // TODO THUMB.13: add offset to stack pointer
    } .elsewhen (in(15, 12) === "b1101".U(4.W)) {
      when (in(11, 8) === "b1111".U(4.W)) {
        // TODO THUMB.17: software interrupt
      } .elsewhen (in(11, 8) =/= "b1110".U(4.W)) {
        // TODO THUMB.16: conditional branch
      }
    } .elsewhen (in(15, 12) === "b1011".U(4.W) && in(10, 9) === "b10".U(2.W)) {
      // TODO THUMB.14: push/pop registers
    } .elsewhen (in(15, 12) === "b1100".U(4.W)) {
      // TODO THUMB.15: multiple load/store
    } .elsewhen (in(15, 11) === "b11100".U(5.W)) {
      // TODO THUMB.18: branch
    } .elsewhen (in(15, 12) === "b1111".U(4.W)) {
      // TODO THUMB.19: branch and link
    }
  }
}
