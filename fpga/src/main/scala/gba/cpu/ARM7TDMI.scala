package gba.cpu

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

/// ARM7TDMI-S compatible processor as found in the GBA
class ARM7TDMI extends Module {
  val io = IO(new Bundle {
    /// Global enable signal for emulation
    val enable = Input(Bool())

    /// Memory bus interface
    val mem = new BusInterface
    /// **Active-High** fast interrupt request
    val FIQ = Input(Bool())
    /// **Active-High** interrupt request
    val IRQ = Input(Bool())
  })

  ////////////////////////////////// Busses and Registers //////////////////////////////////
  val memAddrReg = RegInit(0.U(32.W))
  val memWriteDataReg = Reg(UInt(32.W))
  val memReadDataReg = Reg(UInt(32.W))

  val aBus = Wire(UInt(32.W))
  val bBus = Wire(UInt(32.W))
  val pcBus = Wire(UInt(32.W))
  val aluBus = Wire(UInt(32.W))
  val incrementerBus = Wire(UInt(32.W))
  val control = Wire(new ControlSignals)
  val cpsrBus = Wire(new ProgramStatusRegister)

  //////////////////////////////// Instruction Fetch & Decode //////////////////////////////
  val decodeUnit = Module(new Decoder)
  decodeUnit.io.readData := io.mem.RDATA
  decodeUnit.io.thumb := cpsrBus.thumb

  ////////////////////////////////////// Control Unit //////////////////////////////////////
  val controlUnit = Module(new Control)
  controlUnit.io.enable := io.enable
  controlUnit.io.nextInstruction := decodeUnit.io.decoded
  control := controlUnit.io.signals

  ///////////////////////////////////// Register File //////////////////////////////////////
  // TODO add banked registers
  // TODO add SPSR registers
  val registers = RegInit(VecInit(Seq.fill(16)(0.U(32.W))))
  val cpsr = RegInit((new ProgramStatusRegister).Lit(
    // TODO: should be Supervisor mode
    _.mode -> CpuMode.User,
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
  controlUnit.io.currentStatus := cpsr
  cpsrBus := cpsr
  val pc = registers(15)
  pcBus := pc
  aBus := DontCare
  bBus := DontCare

  when (io.enable) {
    switch (control.pcNext) {
      is (PcNext.Incrementer) { pc := incrementerBus }
    }
  }

  ////////////////////////////////////////// ALU ///////////////////////////////////////////
  val alu = Module(new Alu)
  alu.io.a := DontCare // TODO
  alu.io.b := DontCare // TODO
  alu.io.opcode := DontCare // TODO
  alu.io.flagIn := cpsrBus.cond
  aluBus := alu.io.out

  /////////////////////////////////////// Multiplier ///////////////////////////////////////

  ///////////////////////////////////// Barrel Shifter /////////////////////////////////////

  /////////////////////////////////////// Incrementer //////////////////////////////////////
  incrementerBus := memAddrReg + 4.U // TODO: use current access size

  ///////////////////////////////////////// IO Port ////////////////////////////////////////
  when (io.enable) {
    switch (control.addressNext) {
      is (AddressNext.Incrementer) { memAddrReg := incrementerBus }
      is (AddressNext.Pc) { memAddrReg := pcBus }
      is (AddressNext.Alu) { memAddrReg := aluBus }
    }
  }

  io.mem.ADDR := memAddrReg
  io.mem.WDATA := memWriteDataReg
  io.mem.WRITE := control.memWrite
  io.mem.SIZE := control.memWidth
  io.mem.TRANS := control.memTransaction
  io.mem.LOCK := false.B
  io.mem.PROT.data := false.B
  io.mem.PROT.privileged := false.B
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

  /// 20 bits of padding
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