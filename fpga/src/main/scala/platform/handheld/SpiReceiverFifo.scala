package platform.handheld

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface
import lib.util.ResetSynchronizer
import xilinx.{XpmCdcSingle, XpmFifoAsync}

class SpiReceiverFifo(
  commandWidth: Int = 8,
  addressWidth: Int = 32,
  dataWidth: Int = 32,
  dummyBytes: Int = 8,
) extends Module {
  val io = IO(new Bundle {
    val signals = new SpiSignals

    /** Interface for SPI receiver to access device memory. */
    val mem = Flipped(new MemoryInterface(addressWidth, dataWidth))

    /** Clock for the SPI receiver domain */
    val clockSpi = Input(Clock())
    /** Whether the SPI clock is locked. */
    val clockSpiLocked = Input(Bool())
    /** Whether the SPI clock should be powered down. */
    val clockSpiPowerDown = Output(Bool())

    /** Whether a request FIFO overflow happened in the current transaction. */
    val debugRequestOverflow = Output(Bool())
    /** Whether a response FIFO underflow happened in the current transaction. */
    val debugResponseUnderflow = Output(Bool())
  })

  class FifoRequest extends Bundle {
    val isStart = Bool()
    val inner = UInt(35.W)
  }

  class FifoRequestStart extends Bundle {
    val write = Bool()
    val wordSize = UInt(2.W)
    val address = UInt(dataWidth.W)
  }

  class FifoRequestContinue extends Bundle {
    val autoincrement = Bool() // XXX: can this just be the 0b11 value in wordSize?
    val unused = UInt(2.W)
    val data = UInt(dataWidth.W)
  }

  object State extends ChiselEnum {
    val init = Value
    val writeCommand = Value
    val writeAddress = Value
    val writeData = Value
    val readData = Value
  }

  val spiClock = io.clockSpi
  val systemClock = clock

  // FIFOs
  // Request is SPI -> System, Response is System -> SPI
  val fifoRequest = Module(new XpmFifoAsync(new FifoRequest, 512))
  fifoRequest.io.writeClock := spiClock
  fifoRequest.io.readClock := systemClock
  fifoRequest.io.writeEnable := false.B
  fifoRequest.io.readEnable := false.B
  fifoRequest.io.dataIn := DontCare
  fifoRequest.io.reset := false.B
  val fifoResponse = Module(new XpmFifoAsync(UInt(32.W), depth = 512))
  fifoResponse.io.writeClock := systemClock
  fifoResponse.io.readClock := spiClock
  fifoResponse.io.writeEnable := false.B
  fifoResponse.io.readEnable := false.B
  fifoResponse.io.dataIn := DontCare
  fifoResponse.io.reset := false.B
  val fifoResponseUnderflow = withClock(spiClock)(Reg(Bool()))
  val fifoRequestOverflow = withClock(spiClock)(Reg(Bool()))

  withClock(spiClock) {

    val spiReset = ResetSynchronizer((!io.clockSpiLocked).asAsyncReset)
    withReset(spiReset) {
      // Synchronized signals
      val serialClock = RegNext(RegNext(io.signals.serialClock))
      val serialIn = RegNext(RegNext(io.signals.serialIn))
      val chipSelect = RegNext(RegNext(io.signals.chipSelect))

      // SPI State
      val shiftRegisterLength = commandWidth.max(addressWidth).max(dataWidth)
      val state = RegInit(State.init)
      val shiftInReg = Reg(UInt(shiftRegisterLength.W))
      val shiftInCounter = Reg(UInt((log2Ceil(shiftRegisterLength) + 1).W))
      val shiftOutReg = Reg(UInt(shiftRegisterLength.W))
      val shiftOutCounter = Reg(UInt((log2Ceil(shiftRegisterLength) + 1).W))
      val regCommand = Reg(new SpiCommand)
      val regDummyTimer = Reg(UInt((log2Ceil(dummyBytes) + 1).W))

      val wordSizeInBits = (8.U << regCommand.wordSize).asUInt

      // When true, keeps the fast clock powered up even if nCS goes high.
      val keepAlive = RegInit(false.B)
      io.clockSpiPowerDown := io.signals.chipSelect && !keepAlive

      // SPI I/O
      io.signals.serialOut := VecInit(Seq(
        shiftOutReg(7), shiftOutReg(15), shiftOutReg(31), shiftOutReg(31),
      ))(regCommand.wordSize)

      val prevSerialClock = RegNext(serialClock)
      val risingClock = serialClock && !prevSerialClock
      val fallingClock = !serialClock && prevSerialClock
      when(!chipSelect) {
        // Chip activation: nCS falling edge
        when(RegNext(chipSelect, true.B)) {
          state := State.writeCommand
          shiftInCounter := commandWidth.U
          fifoRequestOverflow := false.B
          fifoResponseUnderflow := false.B
          keepAlive := true.B
        }

        // Rising clock: sample data
        when(risingClock) {
          shiftInReg := Cat(shiftInReg, serialIn)
          shiftInCounter := shiftInCounter - 1.U
        }
        // Falling clock: shift out data
        when(fallingClock) {
          when(state === State.readData && shiftOutCounter === 0.U) {
            // Read the next data.

            // Push read request to FIFO.
            val request = Wire(new FifoRequestContinue)
            request.unused := DontCare
            request.autoincrement := regCommand.autoIncrement
            request.data := DontCare
            fifoRequest.io.dataIn.isStart := false.B
            fifoRequest.io.dataIn.inner := request.asUInt
            when(fifoRequest.io.full) {
              fifoRequestOverflow := true.B
            }.otherwise {
              fifoRequest.io.writeEnable := true.B
            }

            when(regDummyTimer === 0.U) {
              // Read a real word.
              when(fifoResponse.io.empty) {
                shiftOutReg := "hFFFFFFFF".U
                fifoResponseUnderflow := true.B
              }.otherwise {
                // There's a response word present.
                fifoResponse.io.readEnable := true.B
                val data = fifoResponse.io.dataOut

                shiftOutReg := Mux(regCommand.byteSwap,
                  VecInit(Seq(
                    data(7, 0),
                    Cat(data(7, 0), data(15, 8)),
                    Cat(data(7, 0), data(15, 8), data(23, 16), data(31, 24)),
                    data, // XXX: 64-bit not implemented
                  ))(regCommand.wordSize),
                  data
                )
              }
            }.otherwise {
              shiftOutReg := "hFFFFFFFF".U
              regDummyTimer := regDummyTimer - 1.U
            }

            shiftOutCounter := wordSizeInBits - 1.U
          }.otherwise {
            shiftOutReg := shiftOutReg << 1
            shiftOutCounter := shiftOutCounter - 1.U
          }
        }

        when(shiftInCounter === 0.U) {
          switch(state) {
            is(State.writeCommand) {
              // Finished writing command
              regCommand := shiftInReg.asTypeOf(new SpiCommand)
              state := State.writeAddress
              shiftInCounter := addressWidth.U
            }
            is(State.writeAddress) {
              // Finished writing address.
              val address = shiftInReg
              shiftInCounter := wordSizeInBits
              shiftOutCounter := 0.U

              // Push start transfer to request FIFO.
              val request = Wire(new FifoRequestStart)
              request.write := !regCommand.read
              request.wordSize := regCommand.wordSize
              request.address := address
              fifoRequest.io.dataIn.isStart := true.B
              fifoRequest.io.dataIn.inner := request.asUInt
              when(fifoRequest.io.full) {
                fifoRequestOverflow := true.B
              }.otherwise {
                fifoRequest.io.writeEnable := true.B
              }

              when(regCommand.read) {
                state := State.readData
                regDummyTimer := (dummyBytes.U >> regCommand.wordSize)
              }.otherwise {
                state := State.writeData
              }
            }
            is(State.writeData) {
              // Finished writing data.
              val data = shiftInReg

              val request = Wire(new FifoRequestContinue)
              request.unused := DontCare
              request.autoincrement := regCommand.autoIncrement
              request.data := Mux(regCommand.byteSwap,
                VecInit(Seq(
                  data(7, 0),
                  Cat(data(7, 0), data(15, 8)),
                  Cat(data(7, 0), data(15, 8), data(23, 16), data(31, 24)),
                  data, // XXX: 64-bit not implemented
                ))(regCommand.wordSize),
                data
              )
              fifoRequest.io.dataIn.isStart := false.B
              fifoRequest.io.dataIn.inner := request.asUInt
              when(fifoRequest.io.full) {
                fifoRequestOverflow := true.B
              }.otherwise {
                fifoRequest.io.writeEnable := true.B
              }

              shiftInCounter := wordSizeInBits
            }
          }
        }
      }.otherwise {
        state := State.init

        // Don't turn off the SPI clock PLL until the request FIFO has been drained.
        val fifoRequestEmpty = XpmCdcSingle(clock, fifoRequest.io.empty)
        when (fifoRequestEmpty) {
          keepAlive := false.B
        }
      }
    }
  }

  /**
   * System interface logic: runs in system clock domain,
   * operates on the request and response FIFOs.
   */
  val regSysAddress = Reg(UInt(dataWidth.W))
  val regSysWordSize = Reg(UInt(2.W))
  val regSysWrite = Reg(Bool())
  /** Chip select synchronized into system clock domain. */
  val sysChipSelect = RegNext(RegNext(io.signals.chipSelect))

  io.mem.address := regSysAddress
  io.mem.enable := false.B
  io.mem.write := false.B
  io.mem.dataWrite := DontCare
  io.mem.writeStrobe := DontCare

  when (!fifoRequest.io.empty && !fifoRequest.io.readResetBusy) {
    when (fifoRequest.io.dataOut.isStart) {
      val request = fifoRequest.io.dataOut.inner.asTypeOf(new FifoRequestStart)
      regSysAddress := request.address
      regSysWordSize := request.wordSize
      regSysWrite := request.write
      fifoRequest.io.readEnable := true.B
    } .otherwise {
      val request = fifoRequest.io.dataOut.inner.asTypeOf(new FifoRequestContinue)
      when (regSysWrite) {
        io.mem.enable := true.B
        io.mem.write := true.B
        io.mem.dataWrite := request.data
        // TODO: determine based on addresses
        io.mem.writeStrobe := "b1111".U

      } .otherwise {
        io.mem.enable := true.B
        io.mem.write := false.B

        when (io.mem.done) {
          fifoResponse.io.writeEnable := true.B
          fifoResponse.io.dataIn := io.mem.dataRead
        }
      }

      when (io.mem.done) {
        // A write or read operation completed, so confirm and increment.
        fifoRequest.io.readEnable := true.B
        when (request.autoincrement) {
          regSysAddress := regSysAddress + (1.U << regSysWordSize).asUInt
        }
      }
    }
  }

  val regSysPendingReset = RegInit(false.B)
  when (!sysChipSelect && RegNext(sysChipSelect)) {
    // Falling edge of chip select, trigger a reset of the data FIFO first.
    // It's not on the rising edge, because some words might be making
    // their way through the request FIFO still.
    // Could we instead just ignore read requests if nCS is high?
    // I like the idea of nCS going low resetting stuff and putting everything into a good state though.
    regSysPendingReset := true.B
  }
  when (regSysPendingReset && !fifoResponse.io.writeResetBusy) {
    fifoResponse.io.reset := true.B
    regSysPendingReset := false.B
  }

  // Debug output
  io.debugRequestOverflow := XpmCdcSingle(spiClock, fifoRequestOverflow)
  io.debugResponseUnderflow := XpmCdcSingle(spiClock, fifoResponseUnderflow)
}
