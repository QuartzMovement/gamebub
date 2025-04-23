package platform.handheld.boot

import chisel3._
import chisel3.util._
import lib.mem.{MemoryInterface, RegisterMap}
import platform.handheld.HandheldCartridge
import platform.handheld.boot.CartridgeUtility.{CartData, Opcode, State}

object CartridgeUtility {
  object State extends ChiselEnum {
    val idle = Value
    val agbRomRead = Value
  }

  object Opcode extends ChiselEnum {
    val nop = Value
    val agbRomRead = Value
  }

  class CartData extends Bundle {
    val phi = Bool()
    val nWR = Bool()
    val nRD = Bool()
    val nCS = Bool()
    /// nRST (GB) / nCS2 (GBA)
    val pin30 = Bool()
    /// VIN (GB) / nIRQ (GBA)
    val pin31 = Bool()
    /// AD0-15: Lower 16-bit of the address / 16-bit ROM data
    val ADLo = UInt(16.W)
    /// A16-23: Upper 8-bit of ROM address / 16-bit SRAM data
    val ADHi = UInt(8.W)
  }
}

class CartridgeUtility extends Module {
  val io = IO(new Bundle {
    val registers = new MemoryInterface(addressWidth = 16, dataWidth = 32)
    val memInterface = new MemoryInterface(addressWidth = 16, dataWidth = 32)

    val cartridgeEnabled = Output(Bool())
    val cartridge = new HandheldCartridge
  })

  // 64 KiB buffer, 32 bit words with byte mask
  val mem = {
    val mem = SRAM.masked(16 * 1024, Vec(4, UInt(8.W)), numReadPorts = 0, numWritePorts = 0, numReadwritePorts = 2)
    val memHostPort = mem.readwritePorts(0)
    val memDevicePort = mem.readwritePorts(1)

    memHostPort.enable := io.memInterface.enable
    memHostPort.address := io.memInterface.address >> 2
    memHostPort.isWrite := io.memInterface.write
    memHostPort.mask.get := io.memInterface.writeStrobe.asBools
    memHostPort.writeData := io.memInterface.dataWrite.asTypeOf(memHostPort.writeData)
    io.memInterface.dataRead := memHostPort.readData.asUInt
    io.memInterface.done := RegNext(memHostPort.enable)

    memDevicePort
  }

  val regCartEnabled = RegInit(false.B)
  val regCartDir = RegInit(0.U.asTypeOf(new Bundle {
    val ADLo = Bool()
    val ADHi = Bool()
    val pin30 = Bool()
    val pin31 = Bool()
  }))
  val regCartOut = RegInit(0.U.asTypeOf(new CartData))
  val regState = RegInit(State.idle)
  val regInstructionLo = Reg(UInt(32.W))
  val regInstructionHi = Reg(UInt(32.W))
  val doInstructionExecute = WireDefault(false.B)

  val regCartAddress = Reg(UInt(24.W))
  val regMemAddress = Reg(UInt(16.W))
  val regCounterA = Reg(UInt(16.W))
  val regCounterB = Reg(UInt(8.W))

  io.registers <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries = Seq(
      0x0 -> RegisterMap.Entry.rw(regCartEnabled),
      0x4 -> RegisterMap.Entry.r(regState === State.idle),
      0x10 -> RegisterMap.Entry.w(regInstructionLo),
      0x14 -> RegisterMap.Entry.w(regInstructionHi),
      0x18 -> RegisterMap.Entry.w(doInstructionExecute),
    )
  )

  mem.enable := false.B
  mem.address := DontCare
  mem.isWrite := DontCare
  mem.mask.get := DontCare
  mem.writeData := DontCare

  io.cartridgeEnabled := regCartEnabled
  io.cartridge.bank0Dir := regCartDir.ADHi
  io.cartridge.bank1Dir := regCartDir.ADLo
  io.cartridge.bank2Dir := regCartDir.ADLo
  io.cartridge.bank3Dir := true.B
  io.cartridge.pin30Dir := regCartDir.pin30
  io.cartridge.pin31Dir := regCartDir.pin31
  io.cartridge.bank0Out := regCartOut.ADHi
  io.cartridge.bank1Out := regCartOut.ADLo(15, 8)
  io.cartridge.bank2Out := regCartOut.ADLo(7, 0)
  io.cartridge.bank3Out := Cat(
    regCartOut.phi,
    regCartOut.nWR,
    regCartOut.nRD,
    regCartOut.nCS,
  )
  io.cartridge.pin30Out := regCartOut.pin30
  io.cartridge.pin31Out := regCartOut.pin31
  val cartIn = Wire(new CartData)
  cartIn.phi := io.cartridge.bank3In(3)
  cartIn.nWR := io.cartridge.bank3In(2)
  cartIn.nRD := io.cartridge.bank3In(1)
  cartIn.nCS := io.cartridge.bank3In(0)
  cartIn.pin30 := io.cartridge.pin30In
  cartIn.pin31 := io.cartridge.pin31In
  cartIn.ADLo := Cat(io.cartridge.bank1In, io.cartridge.bank2In)
  cartIn.ADHi := io.cartridge.bank0In

  switch (regState) {
    is (State.idle) {
      val instruction = Cat(regInstructionHi, regInstructionLo)
      val opcode = instruction(63, 56)
      when (doInstructionExecute) {
        switch (opcode) {
          is (Opcode.agbRomRead.litValue.U) {
            regState := State.agbRomRead
            regCartAddress := instruction(23, 0)
            regCounterA := instruction(39, 24) // Number of transfers
            regMemAddress := instruction(55, 40)
            regCartOut.phi := false.B
            regCartOut.nWR := true.B
            regCartOut.nRD := true.B
            regCartOut.nCS := true.B
            regCartOut.pin30 := true.B
            regCartOut.pin31 := true.B
            regCartOut.ADHi := instruction(23, 16)
            regCartOut.ADLo := instruction(15, 0)
            regCartDir.ADLo := true.B
            regCartDir.ADHi := true.B
            regCounterB := 0.U
          }
        }
      }
    }
    is (State.agbRomRead) {
      val waitA = 2 // Number of cycles with nCS low and nRD high (non-sequential)
      val waitB = 2 // Number of cycles with nRD low each access
      val transfers = regCounterA
      val cycle = regCounterB
      cycle := cycle + 1.U
      val regData = Reg(UInt(16.W))

      when (cycle === 0.U) {
        regCartOut.nCS := false.B
      }
      when (cycle === waitA.U) {
        regCartOut.nRD := false.B
        regCartDir.ADLo := false.B // Input
      }
      when (cycle === (waitA + waitB).U) {
        regCartOut.nRD := true.B
        regData := cartIn.ADLo
        transfers := transfers - 1.U
      }
      when (cycle === (waitA + waitB + 1).U) {
        // Store in memory
        mem.enable := true.B
        mem.address := regMemAddress(15, 2)
        mem.isWrite := true.B
        mem.mask.get := VecInit(~regMemAddress(1), ~regMemAddress(1), regMemAddress(1), regMemAddress(1))
        mem.writeData := Cat(regData, regData).asTypeOf(mem.writeData)
        regMemAddress := regMemAddress + 2.U

        when (transfers === 0.U) {
          // End transfers
          regState := State.idle
        } .otherwise {
          // Next transfer
          cycle := 1.U
          regCartOut.nRD := false.B
        }
      }
    }
  }
}
