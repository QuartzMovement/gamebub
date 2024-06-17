package gba

import chisel3._
import chisel3.util._

class TimerControl extends Bundle {
  val enable = Bool()
  val irq = Bool()
  val _padding = UInt(3.W)
  val cascade = Bool()
  val freq = UInt(2.W)
}

class Timer extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    val mmio = new MmioTarget()

    val irq = Output(Vec(4, Bool()))
  })

  io.irq := VecInit.fill(4)(false.B)

  val regControl = Seq.fill(4)(RegInit(0.U.asTypeOf(new TimerControl)))
  val regCounterReload = Seq.fill(4)(RegInit(0.U(16.W)))
  val regCounter = Seq.fill(4)(RegInit(0.U(16.W)))

  io.mmio <> MmioMap.fromSeq(
    (0 until 4).map(i => 0x100 + (4 * i) -> MmioMap.Entry(
      MmioMap.ReadFn(regCounter(i), regControl(i)),
      MmioMap.WriteFn(regCounterReload(i), regControl(i)),
    ))
  )

  for (i <- 0 until 4) {
    val control = regControl(i)
    val counter = regCounter(i)
    val counterReload = regCounterReload(i)

    val justEnabled = control.enable && !RegEnable(control.enable, io.enable)
    when (justEnabled) {
      printf(cf"Timer ${i} enable: ${control}\n")
    }
  }
}
