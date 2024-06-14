package gba

import chisel3._
import chisel3.util._
import gba.mem.{BusAccessWidth, BusInterface}

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

    val irq = Output(Vec(4, Bool()))

    val triggerVblank = Input(Bool())
    val triggerHblank = Input(Bool())
    val triggerVideo = Input(Bool())

    val busInitiator = Vec(4, new BusInterface)
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
    val configSource = regConfigSource(i)
    val configDest = regConfigDest(i)
    val configCount = regConfigCount(i)
    val control = regConfigControl(i)
    val bus = io.busInitiator(i)

    val active = RegInit(false.B)
    val regSource = Reg(configSource.cloneType)
    val regDest = Reg(configDest.cloneType)
    val regCount = Reg(configCount.cloneType)
    val regInitial = Reg(Bool())  // Whether this is the initial load-store cycle.
    val regStage = Reg(UInt(1.W))
    val dataLatch = Reg(UInt(32.W))

    val busActive = WireDefault(false.B)
    io.irq(i) := false.B
    bus.WRITE := DontCare
    bus.SIZE := Mux(control.sizeWord, BusAccessWidth.Word, BusAccessWidth.Halfword)
    bus.PROT.data := true.B
    bus.PROT.privileged := false.B
    bus.LOCK := false.B
    bus.ADDR := DontCare
    bus.MREQ := busActive
    bus.SEQ := !regInitial
    bus.WDATA := DontCare

    // Latching config
    val justEnabled = regConfigControl(i).enable && !RegNext(regConfigControl(i).enable)
    val addressMask = Mux(control.sizeWord, "b00".U(2.W), "b10".U(2.W))
    when (io.enable && justEnabled) {
      // TODO Handle special audio fifo config
//      printf(cf"DMA ${i} enable\n")
      // Mask off lower bits of address depending on size
      regSource := Cat(configSource(configSource.getWidth - 1, 2), configSource(1, 0) & addressMask)
      regDest := Cat(configDest(configDest.getWidth - 1, 2), configDest(1, 0) & addressMask)
      regCount := configCount
    }

    // Channel activation
    val activateImm = (control.startControl === DmaStartControl.immediate) && justEnabled
    val activateHblank = (control.startControl === DmaStartControl.hblank) && io.triggerHblank
    val activateVblank = (control.startControl === DmaStartControl.vblank) && io.triggerVblank
    when (io.enable) {
      // TODO handle "special" activation modes / audio FIFO
      when (!active && (activateImm || activateHblank || activateVblank)) {
//        printf(cf"DMA ${i} activate\n")
        active := true.B
        regInitial := true.B
        regStage := 0.U
      }
    }

    // Run DMA
    when (io.enable && active) {
      when (regStage === 0.U) {
        val complete = regCount === 0.U && !regInitial
        // Begin Load (if not end)
        busActive := !complete
        bus.ADDR := regSource
        bus.WRITE := false.B
        // Complete Store (if not initial)
        bus.WDATA := dataLatch  // TODO check if 16-bit works

        // Check if the DMA is complete.
        when (bus.CLKEN) {
          when (complete) {
//            printf(cf"DMA ${i} complete\n")
            io.irq(i) := control.irq
            active := false.B
            // TODO handle different behavior for audio FIFO
            when (control.repeat && control.startControl =/= DmaStartControl.immediate) {
              regCount := configCount
              when (control.destControl === DmaAddressControl.reload) {
                regDest := Cat(configDest(configDest.getWidth - 1, 2), configDest(1, 0) & addressMask)
              }
            } .otherwise {
              control.enable := false.B
            }
          } .otherwise {
//            printf(cf"DMA ${i} - load  @ 0x${regSource}%x\n")
            regStage := 1.U
            regCount := regCount - 1.U

            switch (control.sourceControl) {
              is (DmaAddressControl.increment) {
                regSource := regSource + Mux(control.sizeWord, 4.U, 2.U)
              }
              is (DmaAddressControl.decrement) {
                regSource := regSource - Mux(control.sizeWord, 4.U, 2.U)
              }
            }
          }
        }
      } .otherwise {
        // Complete Load
        when (bus.CLKEN) {
          dataLatch := bus.RDATA  // TODO check if 16-bit works
        }

        // Begin Store
        busActive := true.B
        bus.ADDR := regDest
        bus.WRITE := true.B

        when (bus.CLKEN) {
//          printf(cf"DMA ${i} - store @ 0x${regDest}%x  (data = 0x${bus.RDATA}%x)\n")
          regStage := 0.U
          regInitial := false.B

          switch (control.destControl) {
            is (DmaAddressControl.increment, DmaAddressControl.reload) {
              regDest := regDest + Mux(control.sizeWord, 4.U, 2.U)
            }
            is (DmaAddressControl.decrement) {
              regDest := regDest - Mux(control.sizeWord, 4.U, 2.U)
            }
          }
        }
      }
    }
  }
}