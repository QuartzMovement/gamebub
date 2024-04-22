package gba.cpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

class ARM7TDMISpec extends AnyFunSuite {
  test("basic") {
    simulate(new ARM7TDMI) { dut =>
      dut.io.enable.poke(true)
      dut.io.mem.RDATA.poke(0xFFFFFFFF)
      dut.reset.poke(true)
      dut.clock.step()
      dut.reset.poke(false)

      val data = Array(
        0xe3a00010, // mov r0, 0x10
        0xe3a01cff, // mov r1, 0xFF00
        0xe3a02003, // mov r2, 0x03
        0xe0803001, // add r3, r0, r1
        0xe0833002, // add r3, r3, r2
        0xe1a04213, // mov r4, r3, LSL r2
        0xe2800000, // add r0, r0, 0
        0xe2800001, // add r0, r0, 1
        0xe2800002, // add r0, r0, 2
        0xe2800003, // add r0, r0, 3
        0xe2800004, // add r0, r0, 4
        0xe2800005, // add r0, r0, 5
        0xe2800006, // add r0, r0, 6
        0xe2800007, // add r0, r0, 7
      )

      for (_ <- 0 to 15) {
        val memAddress = dut.io.mem.ADDR.peek().litValue
        val memWrite = dut.io.mem.WRITE.peek().litToBoolean
        val memSize = 1 << dut.io.mem.SIZE.peekValue().asBigInt.toInt
        val memTrans = dut.io.mem.TRANS.peekValue().asBigInt

        dut.clock.step()

        if (memTrans == BusTransactionType.Sequential.litValue || memTrans == BusTransactionType.NonSequential.litValue) {
          val seq = if (memTrans == BusTransactionType.Sequential.litValue) "   Seq" else "NonSeq"
          if (memWrite) {
            val memDataWrite = dut.io.mem.WDATA.peek().litValue
            System.err.println(f"Mem Write $seq: [0x$memAddress%X] <- 0x$memDataWrite%X | size=$memSize\n")
          } else {
            val readData = data.lift(memAddress.toInt >> 2).getOrElse(0xffffffff)
            dut.io.mem.RDATA.poke(readData)
            System.err.println(f"Mem  Read $seq: [0x$memAddress%X] -> 0x$readData%X | size=$memSize\n")
          }
        }
        if (memTrans == BusTransactionType.Internal.litValue) {
          System.err.println(f"Mem          Int: [0x$memAddress%X]\n")
          dut.io.mem.RDATA.poke(0xffffffff)
        }
      }
    }
  }
}
