package gba.cart

import chisel3._
import chisel3.util._

class EmulatedCartridge extends Module {
  val io = IO(new Bundle {
    val interface = Flipped(new CartridgeInterface)

    // TODO: raw ROM/RAM access
  })
}
