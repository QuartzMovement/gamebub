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
  val Undefined = Value
  val DataProcessingImm = Value
  val DataProcessingImmShift = Value
  val DataProcessingRegShift = Value
  val Load = Value
  val Store = Value
  val Swap = Value
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
    val nextInstruction = Input(Bool())
    /// Flush the pipeline
    val flushPipeline = Input(Bool())

    /// Memory read data
    val readData = Input(UInt(32.W))

    /// Decoded instruction
    val decoded = Output(new DecodedInstruction)
  })

  // TODO handle CLOCKEN (bus cycle stretching)
  // Fetch stage, with support for latching the first read value
  // during multi-cycle instructions.
  val isNewFetch = RegNext(io.nextInstruction)
  val fetchReg = RegInit("hFFFFFFFF".U(32.W))
  when (io.enable && (!io.nextInstruction && isNewFetch)) {
    fetchReg := io.readData
  }
  val fetchResult = Mux(isNewFetch, io.readData, fetchReg)

  // Decode stage.
  val decodeReg = RegInit("hFFFFFFFF".U(32.W))
  when (io.enable && io.nextInstruction) {
    decodeReg := fetchResult
  }
  val in = WireDefault(decodeReg)
  printf(cf"decoding ${in}%x, fetching ${fetchResult}%x\n")

  when (io.enable && io.flushPipeline) {
    // TODO correctly flush pipeline
//    fetchReg := "hFFFFFFFF".U(32.W)
    decodeReg := "hFFFFFFFF".U(32.W)
  }

  val out = io.decoded
  out.kind := InstructionKind.Undefined
  out.condition := Condition.Al
  out.regN := DontCare
  out.regD := DontCare
  out.regS := DontCare
  out.regM := DontCare
  out.immediate := DontCare
  out.opcode := DontCare
  out.flags := DontCare

  // Decode table
  when (!io.thumb) {
    // ARM mode
    io.decoded.condition := in(31, 28).asTypeOf(Condition())

    when (in(27, 25) === "b000".U(3.W) && in(4) && in(7)) {
      // Multiply and additional loads/stores
      when (in(7, 4) === "b1001".U(4.W) && in(27, 23) === 0.U) {
        // TODO Multiply [accumulate]
      } .elsewhen (in(7, 4) === "b1001".U(4.W) && in(27, 23) === 1.U) {
        // TODO Multiply [accumulate] long
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
    }
  } .otherwise {
    // Thumb mode
    // TODO
  }
}
