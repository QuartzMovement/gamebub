package gba.link

import chisel3._

class UartTimer(counterWidth: Int = 16) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val baudrate = Input(UInt(2.W))

    /// Pulses high at the beginning of each period
    /// Doesn't pulse high for the first period after reset.
    val pulse = Output(Bool())
    /// Pulses high in the middle of each period
    val pulseMid = Output(Bool())
  })

  io.pulse := false.B
  io.pulseMid := false.B

  val counter = RegInit(0.U(counterWidth.W))
  val increment = VecInit(counterIncrements)(io.baudrate)
  when (io.enable) {
    val nextCounter = counter +& increment
    counter := nextCounter

    io.pulseMid := nextCounter(counterWidth - 1) && !counter(counterWidth - 1)
    io.pulse := nextCounter(counterWidth)
  }

  private def counterIncrements: Seq[UInt] = {
    UartTimer.baudrates.map(baudrate => {
      val clockHz = 16 * 1024 * 1024
      val counterMax = 1 << counterWidth
      (counterMax.toDouble * baudrate.toDouble / clockHz.toDouble).round.toInt.U
    })
  }
}

object UartTimer {
  val baudrates: Seq[Int] = Seq(9600, 38400, 57600, 115200)
}