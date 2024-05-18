package gba

import chisel3._
import chisel3.util._

class Keypad extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val state = Input(new Keypad.State())
    val mmio = new MmioTarget()
  })

  io.mmio <> MmioMap(
    // KEYINPUT and KEYCNT
    0x130 -> MmioMap.Entry.r(
      // TODO KEYCNT
      ~io.state.asUInt
    )
  )

  // TODO interrupt
}

object Keypad {
  class State extends Bundle {
    val l = Bool()
    val r = Bool()
    val down = Bool()
    val up = Bool()
    val left = Bool()
    val right = Bool()
    val start = Bool()
    val select = Bool()
    val b = Bool()
    val a = Bool()
  }
}