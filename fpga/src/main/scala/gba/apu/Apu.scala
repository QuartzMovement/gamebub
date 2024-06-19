package gba.apu

import chisel3._
import chisel3.util._
import gba.{MmioMap, MmioTarget}

class Apu extends Module {
  val io = IO(new Bundle {
    /// Global enable
    val enable = Input(Bool())

    /// MMIO access
    val mmio = new MmioTarget()
  })

  // TODO: SOUNDBIAS is stubbed to allow BIOS to boot
  val regSoundbias = RegInit(0.U(16.W))

  io.mmio <> MmioMap(
    0x88 -> MmioMap.Entry.rw(regSoundbias),
  )
}
