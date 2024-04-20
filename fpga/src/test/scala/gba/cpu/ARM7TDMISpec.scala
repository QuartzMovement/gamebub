package gba.cpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

class ARM7TDMISpec extends AnyFunSuite {
  test("basic") {
    simulate(new ARM7TDMI) { dut =>
      dut.io.enable.poke(true)

      for (_ <- 0 to 10) {
        val memAddress = dut.io.mem.ADDR.peek().litValue
        val memWrite = dut.io.mem.WRITE.peek().litToBoolean
        val memSize = 1 << dut.io.mem.SIZE.peekValue().asBigInt.toInt
        val memTrans = dut.io.mem.TRANS.peekValue().asBigInt

        dut.clock.step()

        if (memTrans == BusTransactionType.Sequential.litValue || memTrans == BusTransactionType.NonSequential.litValue) {
          val seq = if (memTrans == BusTransactionType.Sequential.litValue) "   Seq" else "NonSeq"
          if (memWrite) {
            val memDataWrite = dut.io.mem.WDATA.peek().litValue
            System.err.println(f"Mem Write $seq: [0x$memAddress%X] <- 0x$memDataWrite%X | size=$memSize")
          } else {
            val readData = memAddress
            dut.io.mem.RDATA.poke(readData)
            System.err.println(f"Mem  Read $seq: [0x$memAddress%X] -> 0x$readData%X | size=$memSize")
          }
        }
      }
    }
  }
}
