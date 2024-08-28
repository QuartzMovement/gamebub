package gba.cart.emu

import chisel3._
import chisel3.util._
import gba.cart.emu.Rtc.State
import lib.log.Logger

object Rtc {
  object State extends ChiselEnum {
    val Command = Value
  }
}

class Rtc extends Module {
  val io = IO(new Bundle {
    // TODO add way to set date and time

    /// Serial clock: data sampled on rising edge, set on falling edge. Idles high.
    val serialClock = Input(Bool())
    /// Active high chip-select
    val serialSelect = Input(Bool())
    val serialIn = Input(UInt(1.W))
    val serialOut = Output(UInt(1.W))

    /// Active high interrupt output (probably won't be implemented, seems to be unused).
    val irq = Output(Bool())
  })
  val logger = Logger("cart.emu.rtc")

  val state = RegInit(State.Command)
  val counter = Reg(UInt(6.W))
  val buffer = Reg(UInt(64.W))

  io.irq := false.B
  io.serialOut := 0.U

  val prevSelect = RegNext(io.serialSelect)
  val prevClock = RegNext(io.serialClock)
  when (io.serialSelect) {
    // Rising edge of chip select
    when (!prevSelect) {
      logger.info("RTC selected")
      state := State.Command
      counter := 0.U
    }

    // Rising edge of clock: sample data
    when (io.serialClock && !prevClock) {
      logger.debug(cf"serial  in: ${io.serialIn}")
    }

    // Falling edge of clock: set output
    when (!io.serialClock && prevClock) {
    }
  }
}
