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

  val mode = Wire(Link.Mode.Type())
  val dataIn = io.port.in.asTypeOf(new Link.Ports)
  val prevDataIn = RegNext(io.port.in).asTypeOf(new Link.Ports)

  // RCNT registers
  val regControlMode = RegInit(0.U(2.W))
  val regGpioPinOut = RegInit(0.U(4.W))
  val regGpioPinDir = RegInit(0.U(4.W))
  val regGpioInterrupt = RegInit(false.B)
  // SIOCNT registers
  val regSiocnt = RegInit(0.U(16.W))
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

  // Mode selection
  when (regControlMode(1) === 0.U) {
    when (regSiocnt(13) === 0.U) {
      mode := Link.Mode.Normal
    } .otherwise {
      when (regSiocnt(14) === 0.U) {
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
  io.port.dir := 0.U

  switch (mode) {
    is (Link.Mode.Gpio) {
      io.port.out := regGpioPinOut
      io.port.dir := regGpioPinDir

      when (regGpioInterrupt && !dataIn.si && prevDataIn.si) {
        // SI falling edge interrupt
        io.irq := true.B
      }
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

  object Mode extends ChiselEnum {
    val Normal = Value
    val Multi = Value
    val Uart = Value
    val Gpio = Value
    val Joybus = Value
  }
}