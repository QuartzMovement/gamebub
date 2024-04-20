package gba.cpu

import chisel3._

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
  val DataProcessing = Value
}

class DecodedInstruction extends Bundle {
  val condition = Condition()
  val kind = InstructionKind()
}

class Decoder extends Module {
  val io = IO(new Bundle {
    /// Memory read data
    val readData = Input(UInt(32.W))

    /// Decoded instruction
    val decoded = Output(new DecodedInstruction)
  })

  io.decoded.condition := io.readData(31, 28).asTypeOf(Condition())
  io.decoded.kind := InstructionKind.Undefined
}
