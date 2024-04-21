package gba.cpu

import chisel3._

object PcNext extends ChiselEnum {
  val Same = Value
  val Incrementer = Value
}

object AddressNext extends ChiselEnum {
  val Same = Value
  val Incrementer = Value
  val Pc = Value
  val Alu = Value
}

class ControlSignals extends Bundle {
  val pcNext = PcNext()
  val addressNext = AddressNext()

  val regReadA = UInt(4.W)
  val regReadB = UInt(4.W)
  val regWriteIndex = UInt(4.W)
  val regWriteEnable = Bool()
  val aluOpcode = AluOpcode()

  val memTransaction = BusTransactionType()
  val memWrite = Bool()
  val memWidth = BusAccessWidth()
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

  val loadInstruction = WireDefault(false.B)
  val instruction = Reg(new DecodedInstruction) // TODO: reset?
  when (io.enable && loadInstruction) {
    instruction := io.nextInstruction
  }

  io.signals.pcNext := PcNext.Incrementer
  io.signals.addressNext := AddressNext.Incrementer

  io.signals.regReadA := DontCare
  io.signals.regReadB := DontCare
  io.signals.regWriteIndex := DontCare
  io.signals.regWriteEnable := false.B
  io.signals.aluOpcode := DontCare

  io.signals.memWrite := false.B
  io.signals.memWidth := BusAccessWidth.Word
  io.signals.memTransaction := BusTransactionType.Sequential
}
