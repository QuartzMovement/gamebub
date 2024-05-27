package gba

import chisel3._
import chisel3.util._

class Interrupt extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val mmio = new MmioTarget()

    /// IRQ signal to the CPU
    val irq = Output(Bool())

    /// IRQ signals from peripherals
    val peripheralIrq = Input(new Interrupt.Flags)
  })

  val ime = RegInit(0.U(1.W))
  val regEnabled = RegInit(0.U.asTypeOf(new Interrupt.Flags))
  val regRequested = RegInit(0.U.asTypeOf(new Interrupt.Flags))

  when (io.enable) {
    regRequested := (regRequested.asUInt | io.peripheralIrq.asUInt).asTypeOf(new Interrupt.Flags)
  }
  io.irq := ime(0) && ((regRequested.asUInt & regEnabled.asUInt) =/= 0.U)

  io.mmio <> MmioMap(
    // 0x200: IE, 0x202: IF
    0x200 -> MmioMap.Entry(
      MmioMap.ReadFn(_ => {
        val data = Cat(
          regRequested.asUInt.pad(16),
          regEnabled.asUInt.pad(16),
        )
        (data, true.B)
      }),
      MmioMap.WriteFn((enable, data, mask) => {
        when (enable) {
          // Write to IE
          regEnabled := MMIO.mask(regEnabled, data(15, 0), mask(1, 0))
          // Write to IF
          val ack = MMIO.mask(0.U(16.W), data(31, 16), mask(3, 2))
          regRequested := (regRequested.asUInt & (~ack).asUInt).asTypeOf(new Interrupt.Flags)
        }
      })
    ),
    // 0x208: IME
    0x208 -> MmioMap.Entry.rw(ime),
  )
}

object Interrupt {
  class Flags extends Bundle {
    val cartridge = Bool()
    val keypad = Bool()
    val dma = UInt(4.W)
    val serial = Bool()
    val timer = UInt(4.W)
    val vcount = Bool()
    val hblank = Bool()
    val vblank = Bool()
  }
}