package gba.apu

import chisel3._
import chisel3.util._
import gba.{MmioMap, MmioTarget}

class ApuOutput extends Bundle {
  /** Left sample value */
  val left = SInt(10.W)
  /** Right sample value */
  val right = SInt(10.W)
}

class Apu extends Module {
  val io = IO(new Bundle {
    /// Global enable
    val enable = Input(Bool())

    /// MMIO access
    val mmio = new MmioTarget()

    /// Audio output
    val output = Output(new ApuOutput)

    /// Timer 0 and 1 overflow signal
    val timerOverflow = Input(Vec(2, Bool()))

    /// DMA request trigger for FIFOs A and B
    val dmaTrigger = Output(Vec(2, Bool()))
  })

  io.output.left := 0.S
  io.output.right := 0.S

  io.dmaTrigger := VecInit.fill(2)(false.B)

  // TODO: SOUNDBIAS is stubbed to allow BIOS to boot
  val regSoundbias = RegInit(0.U.asTypeOf(new ApuRegisters.SoundBias))
  val regPsgVolume = RegInit(0.U.asTypeOf(new ApuRegisters.PsgVolume))
  val regPsgPanning = RegInit(0.U.asTypeOf(new ApuRegisters.PsgPanning))
  val regMixControl = RegInit(0.U.asTypeOf(new ApuRegisters.MixControl))
  val regDirectControl = RegInit(0.U.asTypeOf(new ApuRegisters.DirectControl))
  val regMasterEnable = RegInit(false.B)

  io.mmio <> MmioMap(
    0x80 -> MmioMap.Entry(
      MmioMap.ReadFn(regPsgVolume, regPsgPanning, regMixControl, regDirectControl),
      MmioMap.WriteFn((enable, data, mask) => {
        MmioMap.WriteFn(regPsgVolume, regPsgPanning, regMixControl, regDirectControl).fn(enable, data, mask)
        val newDirectControl = data(31, 24).asTypeOf(new ApuRegisters.DirectControl)
        val resetDirectA = mask(3) && newDirectControl.resetA
        val resetDirectB = mask(3) && newDirectControl.resetB
        when (enable) {
          // TODO reset FIFOs
        }
      })
    ),
    0x84 -> MmioMap.Entry(
      MmioMap.ReadFn(_ => {
        // TODO: read-only PSG "on" flag per-channel
        val psgOn = 0.U(4.W)
        (Cat(regMasterEnable, 0.U(3.W), psgOn), true.B)
      }),
      MmioMap.WriteFn((enable, data, mask) => {
        when (enable && mask(0)) {
          val masterEnable = data(7)
          regMasterEnable := masterEnable
          // TODO: reset PSG registers when clearing masterEnable bit
        }
      })
    ),
    0x88 -> MmioMap.Entry.rw(regSoundbias),
  )
}
