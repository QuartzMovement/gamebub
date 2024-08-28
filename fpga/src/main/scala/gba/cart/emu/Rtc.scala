package gba.cart.emu

import chisel3._
import chisel3.util._
import gba.cart.emu.Rtc.State
import lib.log.Logger

object Rtc {
  object State extends ChiselEnum {
    /// Initial state: read the prefix, command, and read/write bit
    val Command = Value
    /// Done state: do nothing until the next activation.
    val Done = Value
    /// Reading a register
    val Read = Value
    /// Writing a register
    val Write = Value
  }

  object Register extends ChiselEnum {
    val Status = Value
    val DateTime = Value
    val Time = Value
  }

  class DateTime extends Bundle {
    val yearLo = UInt(4.W)
    val yearHi = UInt(4.W)
    val monthLo = UInt(4.W)
    val monthHi = UInt(1.W)
    val dayLo = UInt(4.W)
    val dayHi = UInt(2.W)
    val dayOfWeek = UInt(3.W)
    val hourLo = UInt(4.W)
    val hourHi = UInt(2.W)
    val amPm = UInt(1.W)
    val minuteLo = UInt(4.W)
    val minuteHi = UInt(3.W)
    val secondLo = UInt(4.W)
    val secondHi = UInt(3.W)
  }

  class Status extends Bundle {
    val hour24 = Bool()
    val intAe = Bool()
    val intMe = Bool()
    val intFe = Bool()
  }
}

/// RTC chip: Seiko S-3511
class Rtc extends Module {
  val io = IO(new Bundle {
    // TODO add way to get/set date and time

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

  val regDateTime = Reg(new Rtc.DateTime)
  val regStatus = Reg(new Rtc.Status)
  val regRegister = RegInit(Rtc.Register.Status)
  val regState = RegInit(State.Done)
  val regCounter = Reg(UInt(6.W))
  val regBuffer = Reg(UInt(56.W))
  val regOut = RegInit(1.U(1.W))

  io.irq := false.B
  io.serialOut := regOut

  // TODO: tick the time

  val readTime = Cat(
    0.U(1.W),
    regDateTime.secondHi,
    regDateTime.secondLo,
    0.U(1.W),
    regDateTime.minuteHi,
    regDateTime.minuteLo,
    regDateTime.amPm,
    0.U(1.W),
    regDateTime.hourHi,
    regDateTime.hourLo,
  )
  val readDate = Cat(
    0.U(5.W),
    regDateTime.dayOfWeek,
    0.U(2.W),
    regDateTime.dayHi,
    regDateTime.dayLo,
    0.U(3.W),
    regDateTime.monthHi,
    regDateTime.monthLo,
    regDateTime.yearHi,
    regDateTime.yearLo,
  )

  // Serial access
  val prevSelect = RegNext(io.serialSelect)
  val prevClock = RegNext(io.serialClock)
  when (io.serialSelect) {
    // Rising edge of chip select
    when (!prevSelect) {
      logger.info("Selected")
      regState := State.Command
      regCounter := 7.U
    }

    // Rising edge of clock: sample data
    when (io.serialClock && !prevClock) {
//      logger.debug(cf"serial  in: data=${io.serialIn} counter=${regCounter}")

      switch (regState) {
        is (State.Command) {
          regCounter := regCounter - 1.U
          regBuffer := Cat(regBuffer, io.serialIn)

          when (regCounter === 0.U) {
            val prefix = regBuffer(6, 3)
            val command = regBuffer(2, 0)
            val isRead = io.serialIn.asBool
            logger.info(cf"Got command. prefix=${prefix}%b command=${command}%b isRead=${isRead}")

            // Default state: ignore
            regState := State.Done
            regOut := 1.U
            when (prefix === "b0110".U) {
              switch (command) {
                is ("b000".U) {
                  logger.info("Reset")
                  regStatus := 0.U.asTypeOf(new Rtc.Status)
                  regDateTime.yearLo := 0.U
                  regDateTime.yearHi := 0.U
                  regDateTime.monthLo := 1.U
                  regDateTime.monthHi := 0.U
                  regDateTime.dayLo := 1.U
                  regDateTime.dayHi := 0.U
                  regDateTime.dayOfWeek := 0.U
                  regDateTime.hourLo := 0.U
                  regDateTime.hourHi := 0.U
                  regDateTime.amPm := 0.U
                  regDateTime.minuteLo := 0.U
                  regDateTime.minuteHi := 0.U
                  regDateTime.secondLo := 0.U
                  regDateTime.secondHi := 0.U
                }
                is ("b001".U) {
                  // Status register
                  logger.info("Status")
                  when (isRead) {
                    regState := State.Read
                    regBuffer := Cat(
                      0.U(1.W),
                      regStatus.hour24,
                      regStatus.intAe,
                      0.U(1.W),
                      regStatus.intMe,
                      0.U(1.W),
                      regStatus.intFe,
                      0.U(1.W),
                    )
                  } .otherwise {
                    regState := State.Write
                    regRegister := Rtc.Register.Status
                  }
                  regCounter := (8 - 1).U
                }
                is ("b010".U) {
                  // Date and time
                  logger.info("Date and time")
                  when (isRead) {
                    regState := State.Read
                    regBuffer := Cat(readTime, readDate)
                  } .otherwise {
                    regState := State.Write
                    regRegister := Rtc.Register.DateTime
                  }
                  regCounter := (8 * 7 - 1).U
                }
                is ("b011".U) {
                  // Time only
                  logger.info("Time")
                  when (isRead) {
                    regState := State.Read
                    regBuffer := readTime
                  } .otherwise {
                    regState := State.Write
                    regRegister := Rtc.Register.Time
                  }
                  regCounter := (8 * 3 - 1).U
                }
                // Alarm and test mode unimplemented
              }
            }
          }
        }
        is (State.Write) {
          val buffer = Cat(io.serialIn, regBuffer >> 1)
          regBuffer := buffer

          regCounter := regCounter - 1.U
          when (regCounter === 0.U) {
            // TODO
            logger.info(cf"Done write: data=${buffer}%x")
            regState := State.Done
          }
        }
      }
    }

    // Falling edge of clock: set output
    when (!io.serialClock && prevClock) {
      when (regState === State.Read) {
        regOut := regBuffer(0)
        regBuffer := regBuffer >> 1

        regCounter := regCounter - 1.U
        when (regCounter === 0.U) {
          logger.info(cf"Done read")
          regState := State.Done
        }
      }
    }
  }
}
