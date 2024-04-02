package gameboy.cart

import chisel3._
import chisel3.util._

/**
 * IMU state.
 *
 * All accelerometer values are centered around 0x81D0,
 * +/- the acceleration value in 'g's (9.81m/s**2).
 * 1 g is an offset of ~0x70.
 */
class Mbc7ImuState extends Bundle {
  /** Acceleration along X axis */
  val x = UInt(16.W)
  /** Acceleration along Y axis */
  val y = UInt(16.W)
}

class Mbc7 extends Module {
  val io = IO(new MbcIo {
    val imu = Input(new Mbc7ImuState)
  })

  val ramEnable = RegInit(false.B)
  val ramEnable2 = RegInit(false.B)
  val romBank = RegInit(0.U(7.W))

  val accelX = RegInit(0x8000.U(16.W))
  val accelY = RegInit(0x8000.U(16.W))
  // Whether the accel. values have been erased, and can be latched
  val accelErased = RegInit(true.B)

  val eepromClk = RegInit(false.B)
  val eepromCs = RegInit(false.B)
  val eepromDataIn = RegInit(false.B)
  val eepromDataOut = WireDefault(false.B)
  val eeprom = Module(new Mbc7Eeprom)
  eeprom.io.clk := eepromClk
  eeprom.io.cs := eepromCs
  eeprom.io.dataIn := eepromDataIn
  eepromDataOut := eeprom.io.dataOut

  // ROM region writes (ram enable and rom banking)
  when (io.memEnable && io.memWrite && io.selectRom) {
    switch(io.memAddress(14, 13)) {
      is(0.U) {
        ramEnable := io.memDataWrite(3, 0) === "b1010".U
      }
      is(1.U) {
        romBank := io.memDataWrite
      }
      is(2.U) {
        ramEnable2 := io.memDataWrite === 0x40.U
      }
    }
  }
  io.bankRom1 := 0.U
  io.bankRom2 := romBank
  io.bankRam := DontCare

  // RAM region accesses (EEPROM and IMU)
  val dataRead = WireDefault(0xFF.U(8.W))
  when (io.memEnable && !io.selectRom && ramEnable && ramEnable2) {
    val index = io.memAddress(7, 4)
    switch (index) {
      is (0.U) {
        // Erase accelerometer latch
        when (io.memWrite && io.memDataWrite === 0x55.U) {
          accelErased := true.B
          accelX := 0x8000.U
          accelY := 0x8000.U
        }
      }
      is (1.U) {
        // Latch accelerometer
        when (io.memWrite && io.memDataWrite === 0xAA.U && accelErased) {
          accelErased := false.B
          accelX := io.imu.x
          accelY := io.imu.y
        }
      }
      is (2.U) { dataRead := accelX(7, 0) }
      is (3.U) { dataRead := accelX(15, 8) }
      is (4.U) { dataRead := accelY(7, 0) }
      is (5.U) { dataRead := accelY(15, 8) }
      is (6.U) { dataRead := 0x00.U }
      is (7.U) { dataRead := 0xFF.U }
      is (8.U) {
        dataRead := Cat(
          eepromCs, eepromClk, 0.U(4.W), eepromDataIn, eepromDataOut
        )
        when (io.memWrite) {
          eepromDataIn := io.memDataWrite(1)
          eepromClk := io.memDataWrite(6)
          eepromCs := io.memDataWrite(7)

          printf(cf"--write eeprom: cs=${io.memDataWrite(7)} clk=${io.memDataWrite(6)} din=${io.memDataWrite(1)}\n")
        } .otherwise {
          printf(cf"-- read eeprom: dout=${eepromDataOut}\n")
        }
      }
    }
  }
  io.memDataRead := dataRead
  io.ramReadMbc := true.B
}

object Mbc7Eeprom {
  object State extends ChiselEnum {
    /// Initial state: waiting for start condition
    val init = Value
    /// Reading 10 bit command
    val command = Value
    /// Command: read
    val doRead = Value
    /// Command: write (waiting for data)
    val writeData = Value
    /// Command: write (execute)
    val writeExecute = Value
    /// Command: write all (execute)
    val writeAllExecute = Value
    /// Command: erase (wait for falling CS)
    val eraseWait = Value
    /// Command: erase (execute)
    val eraseExecute = Value
    /// Command: erase all (execute)
    val eraseAllExecute = Value
    /// Command done, waiting to reset to initial state
    val done = Value
  }
}

/// Microchip 93LC56
class Mbc7Eeprom extends Module {
  import Mbc7Eeprom.State
  val COUNTER_DELAY = 8192 // at 8MHz, about 1ms

  val io = IO(new Bundle {
    val clk = Input(Bool())
    val cs = Input(Bool())
    val dataIn = Input(Bool())
    val dataOut = Output(Bool())
  })
  val clockPosEdge = io.clk && !RegNext(io.clk)
  val csNegEdge = !io.cs && RegNext(io.cs)
  val writeEnable = RegInit(false.B)
  val command = Reg(UInt(10.W))
  val commandIsAll = command(9, 8) === 0.U // Whether an erase or write command apply to all.
  val counter = Reg(UInt(16.W))
  val address = command(6, 0)
  val data = Reg(UInt(16.W))

  io.dataOut := 1.U

  // TODO: replace with actual storage
  val mem = RegInit(VecInit(Seq.fill(128)(0xFFFF.U(16.W))))

  val state = RegInit(State.init)
  switch (state) {
    is (State.init) {
      when (clockPosEdge && io.cs && io.dataIn) {
        // Start condition detected
        printf(cf"eeprom: start detected\n")
        state := State.command
        counter := 9.U // 10 bits, minus one
      }
    }
    is (State.command) {
      when (clockPosEdge) {
        val nextCommand = Cat(command, io.dataIn.asUInt)
        command := nextCommand
        when (counter === 0.U) {
          // Process command
          printf(cf"eeprom: got command 0x${nextCommand}%x\n")
          switch (nextCommand(9, 8)) {
            is (0.U) {
              switch (nextCommand(7, 6)) {
                is (0.U) {
                  // erase/write disable
                  printf(cf"eeprom: command write disable\n")
                  writeEnable := false.B
                  state := State.done
                }
                is (1.U) {
                  // write all
                  printf(cf"eeprom: command write all\n")
                  state := State.writeData
                }
                is (2.U) {
                  // erase all
                  printf(cf"eeprom: command erase all\n")
                  state := State.eraseWait
                }
                is (3.U) {
                  // erase/write enable
                  printf(cf"eeprom: command write enable\n")
                  writeEnable := true.B
                  state := State.done
                }
              }

            }
            is (1.U) {
              // Write
              printf(cf"eeprom: command write\n");
              when(writeEnable) {
                state := State.writeData
                counter := 15.U
              } .otherwise {
                printf(cf"eeprom: write blocked\n");
                state := State.done
              }
            }
            is (2.U) {
              // Read
              printf(cf"eeprom: command read\n")
              // Prepare to read on the next clock
              counter := 0.U
              data := 0.U
              state := State.doRead
            }
            is (3.U) {
              // Erase
              printf(cf"eeprom: command erase\n")
              when (writeEnable) {
                state := State.eraseWait
              } .otherwise {
                printf(cf"eeprom: erase blocked\n");
                state := State.done
              }
            }
          }
        } .otherwise {
          counter := counter - 1.U
        }
      }
    }
    is (State.doRead) {
      io.dataOut := data(15)
      when (clockPosEdge) {
        data := data << 1
        counter := counter - 1.U
        when (counter === 0.U) {
          // Read the next word.
          printf(cf"    reading: ${mem(address)}%x from 0x${address}%x\n")
          data := mem(address)
          command := command + 1.U
          counter := 15.U
        }
      }
      when (!io.cs) {
        state := State.init
      }
    }
    is (State.writeData) {
      when (clockPosEdge) {
        data := Cat(data, io.dataIn.asUInt)
        counter := counter - 1.U
      }
      when (csNegEdge) {
        printf(cf"   doing write: all=${commandIsAll}, addr=${address}%x, data=${data}%x\n")
        counter := 0.U
        when (commandIsAll) {
          state := State.writeAllExecute
          // TODO: actual write
          mem := VecInit(Seq.fill(128)(data))
        } .otherwise {
          state := State.writeExecute
          // TODO: actual write
          mem(address) := data
        }
      }
    }
    is (State.writeExecute) {
      // "Write takes 4ms per word typical"
      io.dataOut := 0.U
      counter := counter + 1.U
      when (counter === COUNTER_DELAY.U) {
        printf(cf"    ! write DONE\n")
        state := State.done
      }
    }
    is (State.writeAllExecute) {
      // "Write all takes 16ms per word typical"
      io.dataOut := 0.U
      counter := counter + 1.U
      when(counter === COUNTER_DELAY.U) {
        printf(cf"    ! write ALL DONE\n")
        state := State.done
      }
    }
    is (State.eraseWait) {
      when(csNegEdge) {
        printf(cf"   doing erase: all=${commandIsAll}, addr=${address}%x\n")
        counter := 0.U
        when (commandIsAll) {
          state := State.eraseAllExecute
          // TODO: actual erase
          mem := VecInit(Seq.fill(128)(0xFFFF.U))
        }.otherwise {
          state := State.eraseExecute
          // TODO: actual erase
          mem(address) := 0xFFFF.U
        }
      }
    }
    is (State.eraseExecute) {
      // "Erase takes 4ms per word typical"
      io.dataOut := 0.U
      counter := counter + 1.U
      when(counter === COUNTER_DELAY.U) {
        printf(cf"    ! erase DONE\n")
        state := State.done
      }
    }
    is(State.eraseAllExecute) {
      // "Erase all takes 8ms per word typical"
      io.dataOut := 0.U
      counter := counter + 1.U
      when(counter === COUNTER_DELAY.U) {
        printf(cf"    ! erase ALL DONE\n")
        state := State.done
      }
    }
    is (State.done) {
      when (!io.cs) {
        state := State.init
      }
    }
  }
}