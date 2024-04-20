package gba.cpu

import chisel3._

object BusAccessWidth extends ChiselEnum {
    /// 8-bit byte
    val Byte = Value
    /// 16-bit halfword
    val Halfword = Value
    /// 32-bit word
    val Word = Value
}

object BusTransactionType extends ChiselEnum {
    /// Internal (address-only)
    val Internal = Value
    /// Coprocessor
    val Coprocessor = Value
    /// Memory access at non-sequential address
    val NonSequential = Value
    /// Memory access at sequential burst address.
    val Sequential = Value
}

class BusProtectionType extends Bundle {
    /// True for a privileged access
    val privileged = Bool()
    /// True if the access is data, otherwise code
    val data = Bool()
}

/// ARM7TDMI memory interface, excluding CLK and nRESET
class BusInterface extends Bundle {
    /// Wait state control (low to cause a wait state)
    val CLKEN = Input(Bool())

    /// Write/read access, high for write
    val WRITE = Output(Bool())
    /// Memory access width.
    val SIZE = Output(BusAccessWidth())
    /// Signals whether the output is code or data, and whether the access is User mode or privileged
    val PROT = Output(new BusProtectionType)
    /// Locked transaction operation
    val LOCK = Output(Bool())

    /// Output address bus
    val ADDR = Output(UInt(32.W))
    /// Next transaction type
    val TRANS = Output(BusTransactionType())

    /// Memory abort or bus error
    val ABORT = Input(Bool())
    /// Write data output bus
    val WDATA = Output(UInt(32.W))
    /// Read data input bus
    val RDATA = Input(UInt(32.W))
}
