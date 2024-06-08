package gba

import chisel3._
import chisel3.util._

object DmaAddressControl extends ChiselEnum {
  val increment = Value
  val decrement = Value
  val fixed = Value
  val reload = Value
}

object DmaStartControl extends ChiselEnum {
  val immediate = Value
  val vblank = Value
  val hblank = Value
  val special = Value
}

class DmaControl extends Bundle {
  val enable = Bool()
  val irq = Bool()
  val startControl = DmaStartControl()
  val cartridgeDrq = Bool()
  val sizeWord = Bool()
  val repeat = Bool()
  val sourceControl = DmaAddressControl()
  val destControl = DmaAddressControl()
  val _padding = UInt(5.W)
}

class Dma extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val mmio = new MmioTarget()
  })

  val regConfigSource = Seq(RegInit(0.U(27.W)), RegInit(0.U(28.W)), RegInit(0.U(28.W)), RegInit(0.U(28.W)))
  val regConfigDest = Seq(RegInit(0.U(27.W)), RegInit(0.U(27.W)), RegInit(0.U(27.W)), RegInit(0.U(28.W)))
  val regConfigCount = Seq(RegInit(0.U(14.W)), RegInit(0.U(14.W)), RegInit(0.U(14.W)), RegInit(0.U(16.W)))
  val regConfigControl = Seq.fill(4)(RegInit(0.U.asTypeOf(new DmaControl)))

  io.mmio <> MmioMap.fromSeq(
    (0 until 4).flatMap(i => Seq(
      (0xB0 + (i * 12)) -> MmioMap.Entry.w(regConfigSource(i)),
      (0xB4 + (i * 12)) -> MmioMap.Entry.w(regConfigDest(i)),
      (0xB8 + (i * 12)) -> MmioMap.Entry(
        MmioMap.ReadFn(0.U(8.W), regConfigControl(i)),
        MmioMap.WriteFn(regConfigCount(i), regConfigControl(i))
      )
    ))
  )

  for (i <- 0 until 4) {
    when (regConfigControl(i).enable && !RegNext(regConfigControl(i).enable)) {
      printf(cf"DMA enable ${i}:\n  src=${regConfigSource(i)}%x\n  dest=${regConfigDest(i)}%x\n  ${regConfigControl(i)}\n\n")
    }
  }
}