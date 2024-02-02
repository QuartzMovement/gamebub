package platform.handheld

import chisel3._
import chisel3.util._

/** Raw SPI signals, unsynchronized. */
class SpiSignals extends Bundle {
  val serialClock = Input(Bool())
  val serialIn = Input(Bool())
  /** Serial out, high-z if chip-select is high. */
  val serialOut = Output(Bool())
  /** Active-low chip select */
  val chipSelect = Input(Bool())
  // TODO implement QSPI
}

object SpiState extends ChiselEnum {
  val writeCommand = Value
  val writeAddress = Value
  val writeData = Value
  val readData = Value
}

class SpiCommand extends Bundle {
  /** Bit 0: 1 if the command is controller reading a register */
  val isRead = Bool()
}

/**
 * SPI receiver, half-duplex
 *
 * Chip-select: active-low
 * Polarity: 0 (clock idles low), data sampled on rising edge
 */
class SpiReceiver(
  commandLength: Int = 8,
  addressLength: Int = 16,
  dataLength: Int = 16,
) extends Module {
  val io = IO(new Bundle {
    val signals = new SpiSignals

    val address = Output(UInt(addressLength.W))
    val dataWrite = Output(UInt(dataLength.W))
    val dataRead = Input(UInt(dataLength.W))
    val readValid = Output(Bool())
    val writeValid = Output(Bool())
  })

  // Synchronized signals
  val serialClock = RegNext(RegNext(io.signals.serialClock))
  val serialIn = RegNext(RegNext(io.signals.serialIn))
  val chipSelect = RegNext(RegNext(io.signals.chipSelect))

  // State
  val shiftRegisterLength = commandLength.max(addressLength).max(dataLength)
  val state = Reg(SpiState())
  val shiftInReg = Reg(UInt(shiftRegisterLength.W))
  val shiftInCounter = Reg(UInt((log2Ceil(shiftRegisterLength) + 1).W))
  val shiftOutReg = Reg(UInt(shiftRegisterLength.W))
  val shiftOutCounter = Reg(UInt((log2Ceil(shiftRegisterLength) + 1).W))
  val regCommand = Reg(new SpiCommand)
  val regAddress = Reg(UInt(addressLength.W))

  // I/O
  io.signals.serialOut := shiftOutReg(dataLength - 1)
  io.address := regAddress
  io.dataWrite := shiftInReg
  io.readValid := false.B
  io.writeValid := false.B

  val prevSerialClock = RegNext(serialClock)
  val risingClock = serialClock && !prevSerialClock
  val fallingClock = !serialClock && prevSerialClock

  when (!chipSelect) {
    // Chip activation: nCS falling edge
    when (RegNext(chipSelect)) {
      state := SpiState.writeCommand
      shiftInCounter := commandLength.U
      //    printf(cf"    Chip selected\n")
    }

    // Rising clock: sample data
    when (risingClock) {
      shiftInReg := Cat(shiftInReg, serialIn)
      shiftInCounter := shiftInCounter - 1.U
      //    printf(cf"    RisingClock; state = ${state}, nextShift = ${Cat(shiftInReg, serialIn)}%b\n")
    }
    // Falling clock: shift out data
    when (fallingClock) {
      when (state === SpiState.readData && shiftOutCounter === 0.U) {
        // Read the next data.
        io.readValid := true.B
        shiftOutReg := io.dataRead
        shiftOutCounter := dataLength.U - 1.U
        regAddress := regAddress + (dataLength / 8).U
        //      printf(cf"    > Sending data: ${io.dataRead}%x\n")
      }.otherwise {
        shiftOutReg := shiftOutReg << 1
        shiftOutCounter := shiftOutCounter - 1.U
      }
      //    printf(cf"    FallingClock; state = ${state}, nextShift = ${shiftOutReg << 1}%b\n")
    }

    when(shiftInCounter === 0.U) {
      switch(state) {
        is (SpiState.writeCommand) {
          // Finished writing command
          //        printf(cf"    > Got command: ${shiftInReg}%x\n")
          regCommand := shiftInReg.asTypeOf(new SpiCommand)
          state := SpiState.writeAddress
          shiftInCounter := addressLength.U
        }
        is (SpiState.writeAddress) {
          // Finished writing address
          //        printf(cf"    > Got address: ${shiftInReg}%x\n")
          regAddress := shiftInReg
          shiftInCounter := dataLength.U
          shiftOutCounter := 0.U

          when(regCommand.isRead) {
            state := SpiState.readData
          }.otherwise {
            state := SpiState.writeData
          }
        }
        is (SpiState.writeData) {
          // Finished writing data
          //        printf(cf"    > Received data: ${shiftInReg}%x\n")
          io.writeValid := true.B
          shiftInCounter := dataLength.U
          regAddress := regAddress + (dataLength / 8).U // XXX: check auto-increment write behavior
        }
      }
    }
  }
}
