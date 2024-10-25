package gba.link

import chisel3._
import chisel3.util._
import gba.{MmioMap, MmioTarget}
import lib.log.Logger

class Link extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val mmio = new MmioTarget()
    val irq = Output(Bool())

    val port = new Link.Interface
  })
  val logger = Logger("link", enable = io.enable)

  // RCNT registers
  val regControlMode = RegInit(0.U(2.W))
  val regGpioPinOut = RegInit(0.U(4.W))
  val regGpioPinDir = RegInit(0.U(4.W))
  val regGpioInterrupt = RegInit(false.B)

  val regSiocnt = RegInit(0.U(15.W))
  val regSiodata8 = RegInit(0.U(16.W))

  io.irq := false.B
  io.mmio <> MmioMap(
    // SIOCNT
    0x128 -> MmioMap.Entry.rw16(regSiocnt, regSiodata8),
    // RCNT
    0x134 -> MmioMap.Entry(
      MmioMap.ReadFn({
        val out = Wire(new Link.RegisterRcnt)
        out.pinData := io.port.in
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

  val regTimer = Reg(UInt(11.W))
  val regActive = RegInit(false.B)

  // TODO: this is stubbed
  when (io.enable) {
    when (!regActive && regSiocnt(7)) {
      logger.info("SIO activate")
      regActive := true.B

      val clockFast = regSiocnt(1)
      val transfer32 = regSiocnt(12)
      when (clockFast) {
        regTimer := Mux(transfer32, 255.U, 63.U)
      } .otherwise {
        regTimer := Mux(transfer32, 2047.U, 511.U)
      }
    }

    when (regActive) {
      regTimer := regTimer - 1.U
      when (regTimer === 0.U) {
        logger.info("SIO complete")
        regActive := false.B
        val irqEnabled = regSiocnt(14)
        when (irqEnabled) {
          io.irq := true.B
        }
        // Unset active bit.
        regSiocnt := Cat(regSiocnt(14, 8), 0.U(1.W), regSiocnt(6, 0))
      }
    }
  }

  // Stubbed: all inputs (high-z)
  io.port.out := DontCare
  io.port.dir := 0.U
}

object Link {
  /// Link port: [3=SO, 2=SI, 1=SD, 0=SC]
  class Interface extends Bundle {
    val in = Input(UInt(4.W))
    val out = Output(UInt(4.W))
    val dir = Output(UInt(4.W))
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
}