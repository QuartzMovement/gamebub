package gba.link

import chisel3._
import chisel3.util._
import gba.{MMIO, MmioMap, MmioTarget}
import lib.log.Logger

class Link extends Module {
  val io = IO(new Bundle {
    // TODO: handle properly
    val enable = Input(Bool())
    val mmio = new MmioTarget()
    val irq = Output(Bool())

    val port = new Link.Interface
  })
  val logger = Logger("link", enable = io.enable)

  val mode = Wire(Link.Mode.Type())
  val prevMode = RegNext(mode)
  val prevPortIn = RegNext(io.port.in)
  /// Whether bit 7 of SIOCNT has been written this cycle (only for Normal and Multi mode).
  val siocntStartSet = WireDefault(false.B)
  val siocntStartUnset = WireDefault(false.B)
  /// SIOCNT read value (mode-dependent), low 8-bits. Upper 8 bits are all R/W and shared
  val siocntReadValueLo = WireDefault(0.U(8.W))

  // RCNT registers
  val regControlMode = RegInit(0.U(2.W))
  val regGpioPinOut = RegInit(0.U(4.W))
  val regGpioPinDir = RegInit(0.U(4.W))
  val regGpioInterrupt = RegInit(false.B)
  // SIOCNT register
  val regSiocnt = RegInit(0.U(15.W))
  // 0x12A: SIODATA8 / SIOMLT_SEND
  val regDataA = RegInit(0.U(16.W))
  // 0x120: SIODATA32_L / SIOMULTI0
  val regDataB0 = RegInit(0.U(16.W))
  // 0x122: SIODATA32_H / SIOMULTI1
  val regDataB1 = RegInit(0.U(16.W))
  // 0x124: SIOMULTI2
  val regDataB2 = RegInit(0.U(16.W))
  // 0x126: SIOMULTI3
  val regDataB3 = RegInit(0.U(16.W))

  io.irq := false.B
  io.mmio <> MmioMap(
    0x120 -> MmioMap.Entry.rw16(regDataB0, regDataB1),
    0x124 -> MmioMap.Entry.rw16(regDataB2, regDataB3),
    // SIOCNT
    0x128 -> MmioMap.Entry(
      MmioMap.ReadFn(Cat(regDataA, 0.U(1.W), regSiocnt(14, 8), siocntReadValueLo)),
      MmioMap.WriteFn((enable, data, mask) => {
        when (enable) {
          regSiocnt := MMIO.mask(regSiocnt, data(15, 0), mask(1, 0))
          regDataA := MMIO.mask(regDataA, data(31, 16), mask(3, 2))
          siocntStartSet := mask(0) && data(7)
          siocntStartUnset := mask(0) && !data(7)
        }
      })
    ),
    // RCNT
    0x134 -> MmioMap.Entry(
      MmioMap.ReadFn({
        val out = Wire(new Link.RegisterRcnt)
        out.pinData := io.port.in.asUInt
        out.pinDir := regGpioPinDir
        out.interrupt := regGpioInterrupt
        out._unused := 0.U
        out.mode := regControlMode
        out
      }),
      MmioMap.WriteFn((enable, rawData, mask) => {
        val data = rawData.asTypeOf(new Link.RegisterRcnt)
        when (enable && mask(0)) {
          regGpioPinOut := data.pinData
          regGpioPinDir := data.pinDir
        }
        when (enable && mask(1)) {
          regGpioInterrupt := data.interrupt
          regControlMode := data.mode
        }
      })
    ),
  )

  // Mode selection
  when (regControlMode(1) === 0.U) {
    when (regSiocnt(13) === 0.U) {
      mode := Link.Mode.Normal
    } .otherwise {
      when (regSiocnt(12) === 0.U) {
        mode := Link.Mode.Multi
      } .otherwise {
        mode := Link.Mode.Uart
      }
    }
  } .otherwise {
    when (regControlMode(0) === 0.U) {
      mode := Link.Mode.Gpio
    } .otherwise {
      mode := Link.Mode.Joybus
    }
  }

  // Stubbed: all inputs (high-z)
  io.port.out := DontCare
  io.port.out.sd := false.B
  io.port.dir.si := false.B
  io.port.dir.so := false.B
  io.port.dir.sd := true.B
  io.port.dir.sc := false.B

  val uartTimer = Module(new UartTimer)
  uartTimer.io.enable := io.enable
  uartTimer.io.baudrate := DontCare

  switch (mode) {
    is (Link.Mode.Multi) {
      handleMulti()
    }
    is (Link.Mode.Gpio) {
      handleGpio()
    }
  }

  private def handleGpio(): Unit = {
    io.port.out := regGpioPinOut.asTypeOf(new Link.Ports)
    io.port.dir := regGpioPinDir.asTypeOf(new Link.Ports)

    when (regGpioInterrupt && !io.port.in.si && prevPortIn.si) {
      // SI falling edge interrupt
      io.irq := true.B
    }
  }

  private def handleMulti(): Unit = {
    val isMaster = !io.port.in.si
    val rxDataRegs = Seq(regDataB0, regDataB1, regDataB2, regDataB3)

    val regMyId = RegInit(0.U(2.W))
    val regError = RegInit(false.B)
    val regState = RegInit(Link.MultiState.Idle)
    val regBusy = RegInit(false.B)
    when (io.enable && prevMode =/= Link.Mode.Multi) {
      regState := Link.MultiState.Idle
    }

    // SIOCNT
    val siocntLo = regSiocnt.asTypeOf(new Link.MultiSiocntLo)
    val siocntLoRead = Wire(new Link.MultiSiocntLo)
    siocntReadValueLo := siocntLoRead.asUInt
    siocntLoRead.baud := siocntLo.baud
    siocntLoRead.si := !isMaster
    siocntLoRead.sd := io.port.in.sd
    siocntLoRead.id := Mux(isMaster, 0.U, regMyId)
    siocntLoRead.error := regError
    siocntLoRead.busy := regBusy
    val interruptEnable = regSiocnt(14)

    uartTimer.io.baudrate := siocntLo.baud

    io.port.dir.so := true.B  // Always Output
    io.port.dir.si := false.B // Always Input
    io.port.dir.sd := false.B // Default: input
    io.port.dir.sc := isMaster && (regState =/= Link.MultiState.Idle)
    io.port.out.so := true.B  // Default: high
    io.port.out.si := DontCare
    io.port.out.sd := DontCare
    io.port.out.sc := false.B

    val regPeerId = Reg(UInt(2.W))
    val regTxBuffer = Reg(UInt(18.W))
    val regRxBuffer = Reg(UInt(18.W))
    val regPulseCounter = Reg(UInt(5.W))
    val regWaitCounter = Reg(UInt(9.W))
    val regDidTransmit = Reg(Bool())

    switch (regState) {
      is (Link.MultiState.Idle) {
        val startMaster = isMaster && siocntStartSet
        val startSlave = !isMaster && !io.port.in.sc
        when (io.enable && (startMaster || startSlave)) {
          logger.info(cf"Multi begin: master=${isMaster}")

          regDidTransmit := false.B
          rxDataRegs.foreach(r => r := 0xFFFF.U)
          uartTimer.reset := true.B
          regPeerId := 0.U
          regError := false.B
          regTxBuffer := Cat(1.U(1.W), regDataA, 0.U(1.W))
          regPulseCounter := 17.U // (1 start, 16 data, 1 stop) minus 1
          regBusy := true.B

          when (isMaster) {
            regState := Link.MultiState.MasterTransmit
          } .otherwise {
            regState := Link.MultiState.SlaveReceive
          }
        }
        when (siocntStartSet) {
          regBusy := true.B
        }
      }
      is (Link.MultiState.MasterTransmit) {
        io.port.dir.sd := true.B
        io.port.out.sd := regTxBuffer(0)
        // TODO: should be receiving here too?

        when (io.enable && uartTimer.io.pulse) {
          logger.debug("Master TX pulse")
          regTxBuffer := regTxBuffer >> 1
          regPulseCounter := regPulseCounter - 1.U
          when (regPulseCounter === 0.U) {
            logger.debug("Master TX end")
            regState := Link.MultiState.MasterWait
            regWaitCounter := 0.U
            regDataB0 := regDataA // XXX: should instead be storing what is seen on input pins?
          }
        }
      }
      is (Link.MultiState.MasterWait) {
        io.port.out.so := false.B

        val nextWaitCounter = regWaitCounter + 1.U
        when (io.enable) {
          regWaitCounter := nextWaitCounter

          when (!io.port.in.sd) {
            logger.debug("Master: saw slave start bit")
            regPeerId := regPeerId + 1.U
            regState := Link.MultiState.MasterReceive
            regPulseCounter := 17.U
            uartTimer.reset := true.B
          } .elsewhen (nextWaitCounter === 0.U) {
            logger.debug("Master: wait timed out")
            io.irq := interruptEnable
            regBusy := false.B
            regState := Link.MultiState.Idle
          }
        }
      }
      is (Link.MultiState.MasterReceive) {
        io.port.out.so := false.B

        when (io.enable && uartTimer.io.pulseMid) {
          logger.debug("Master RX mid-pulse")
          regRxBuffer := Cat(io.port.in.sd, regRxBuffer >> 1)
        }
        when (io.enable && uartTimer.io.pulse) {
          logger.debug("Master RX pulse")
          regPulseCounter := regPulseCounter - 1.U
          when (regPulseCounter === 0.U) {
            logger.debug("Master RX end")
            val rxData = regRxBuffer(16, 1)
            rxDataRegs.zipWithIndex.foreach(r => {
              when (regPeerId === r._2.U) {
                r._1 := rxData
              }
            })

            when (regPeerId === 3.U) {
              logger.debug("Master: slave 3 ended")
              io.irq := interruptEnable
              regBusy := false.B
              regState := Link.MultiState.Idle
            } .otherwise {
              regState := Link.MultiState.MasterWait
              regWaitCounter := 0.U
            }
          }
        }
      }
      is (Link.MultiState.SlaveReceive) {
        io.port.out.so := !regDidTransmit

        when (io.enable && uartTimer.io.pulseMid) {
          logger.debug("Slave RX mid-pulse")
          regRxBuffer := Cat(io.port.in.sd, regRxBuffer >> 1)
        }
        when (io.enable && uartTimer.io.pulse) {
          logger.debug("Slave RX pulse")
          regPulseCounter := regPulseCounter - 1.U
          when (regPulseCounter === 0.U) {
            logger.debug("Slave RX end")
            val rxData = regRxBuffer(16, 1)
            rxDataRegs.zipWithIndex.foreach(r => {
              when (regPeerId === r._2.U) {
                r._1 := rxData
              }
            })

            regState := Link.MultiState.SlaveWait
            regPeerId := regPeerId + 1.U
          }
        }
      }
      is (Link.MultiState.SlaveWait) {
        io.port.out.so := !regDidTransmit

        when (io.enable && !io.port.in.sd) {
          logger.debug("Slave wait: got start bit")
          // Go to Receive
          uartTimer.reset := true.B
          regPulseCounter := 17.U // (1 start, 16 data, 1 stop) minus 1
          regState := Link.MultiState.SlaveReceive
        }
        when (io.enable && !io.port.in.si && !regDidTransmit) {
          logger.debug("Slave wait: saw SI go low")
          // Go to Transmit
          uartTimer.reset := true.B
          regMyId := regPeerId
          regPulseCounter := 17.U // (1 start, 16 data, 1 stop) minus 1
          regState := Link.MultiState.SlaveTransmit
          regDidTransmit := true.B
        }
        when (io.enable && io.port.in.sc) {
          logger.debug("Slave wait: SC went back high")
          // End
          io.irq := interruptEnable
          regBusy := false.B
          regState := Link.MultiState.Idle
        }
      }
      is (Link.MultiState.SlaveTransmit) {
        io.port.dir.sd := true.B
        io.port.out.sd := regTxBuffer(0)
        // TODO: should be receiving here too?

        when (io.enable && uartTimer.io.pulse) {
          logger.debug("Slave TX pulse")
          regTxBuffer := regTxBuffer >> 1
          regPulseCounter := regPulseCounter - 1.U
          when (regPulseCounter === 0.U) {
            logger.debug("Slave TX end")
            // XXX: should instead be storing what is seen on input pins?
            rxDataRegs.zipWithIndex.foreach(r => {
              when (regMyId === r._2.U) {
                r._1 := regDataA
              }
            })
            regState := Link.MultiState.SlaveWait
          }
        }
      }
    }

    when (regState =/= Link.MultiState.Idle && siocntStartUnset) {
      logger.debug("Abort multi")
      regBusy := false.B
      regState := Link.MultiState.Idle
    }
  }
}

object Link {
  class Ports extends Bundle {
    val so = Bool()
    val si = Bool()
    val sd = Bool()
    val sc = Bool()
  }

  /// Link port: [3=SO, 2=SI, 1=SD, 0=SC]
  class Interface extends Bundle {
    val in = Input(new Ports)
    val out = Output(new Ports)
    val dir = Output(new Ports)
  }

  /// RCNT register: SIO mode / GPIO
  class RegisterRcnt extends Bundle {
    val mode = UInt(2.W)
    val _unused = UInt(5.W)
    /// SI interrupt enable
    val interrupt = Bool()
    val pinDir = UInt(4.W)
    val pinData = UInt(4.W)
  }

  object Mode extends ChiselEnum {
    val Normal = Value
    val Multi = Value
    val Uart = Value
    val Gpio = Value
    val Joybus = Value
  }

  class MultiSiocntLo extends Bundle {
    val busy = Bool()
    val error = Bool()
    val id = UInt(2.W)
    val sd = Bool()
    val si = Bool()
    val baud = UInt(2.W)
  }

  /// States for the multiplayer state machine
  object MultiState extends ChiselEnum {
    /// Idle State: waiting for a transfer to begin
    val Idle = Value
    /// Master: Sending out our data
    val MasterTransmit = Value
    /// Master: Waiting for a slave to start sending data
    val MasterWait = Value
    /// Master: Receiving data from a slave
    val MasterReceive = Value
    /// Slave: Receiving data from another peer
    val SlaveReceive = Value
    /// Slave: Waiting for next peer to start
    val SlaveWait = Value
    /// Slave: Sending out our data
    val SlaveTransmit = Value
  }
}