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
    /// Thumb mode
    val thumb = Input(Bool())

    /// Memory read data
    val readData = Input(UInt(32.W))

    /// Decoded instruction
    val decoded = Output(new DecodedInstruction)
  })

  val fetchReg = RegInit(0xFFFFFF.U(32.W))
  fetchReg := io.readData
  val in = WireDefault(fetchReg)

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
    io.decoded.condition := fetchReg(31, 28).asTypeOf(Condition())

    when (in(27, 26) === "b000".U(3.W) && in(4) && in(7)) {
      // Multiply and additional loads/stores
      // TODO
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
    }
  } .otherwise {
    // Thumb mode
    // TODO
  }
}
