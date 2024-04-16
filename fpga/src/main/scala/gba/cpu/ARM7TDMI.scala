package gba.cpu

import chisel3._
import chisel3.util._

/// ARM7TDMI-S compatible processor as found in the GBA
class ARM7TDMI extends Module {
  val io = IO(new Bundle {
    /// Global enable signal for emulation
    val enable = Input(Bool())

    val bus = new BusInterface
    /// **Active-High** fast interrupt request
    val FIQ = Input(Bool())
    /// **Active-High** interrupt request
    val IRQ = Input(Bool())
  })
}
