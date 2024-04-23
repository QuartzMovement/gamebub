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
        0xe3a00010, // 0x00: mov r0, 0x10
        0xe3a01cff, // 0x04: mov r1, 0xFF00
        0xe3a02003, // 0x08: mov r2, 0x03
        0xe0803001, // 0x0C: add r3, r0, r1
        0xe0833002, // 0x10: add r3, r3, r2
        0xe1a04213, // 0x14: mov r4, r3, LSL r2
        0xe3a00000, // 0x18: mov r0, 0
        0xe3a00001, // 0x1C: mov r0, 1
        0xe3a00002, // 0x20: mov r0, 2
        0xe3a00003, // 0x24: mov r0, 3
        0xe3a00004, // 0x28: mov r0, 4
        0xe3a00005, // 0x2C: mov r0, 5
        0xe3a00006, // 0x30: mov r0, 6
        0xe3a00010, // 0x34: mov r0, 0x10
        0xe3a0f018, // 0x38: mov pc, 0x18
        0xe3a06010, // 0x3C: mov r6, 0x10
        0xe3a06020, // 0x40: mov r6, 0x20
        0xe3a06030, // 0x44: mov r6, 0x30
      )

//      val data = Array(
//        0xe3a0f020, // mov pc, 0x20
//        0xe2800000, // add r0, r0, 0
//        0xe2800001, // add r0, r0, 1
//        0xe2800002, // add r0, r0, 2
//        0xe2800003, // add r0, r0, 3
//        0xe2800000, // add r0, r0, 4
//        0xe2800000, // add r0, r0, 5
//        0xe2800000, // add r0, r0, 6
//        0xe3a00010, // mov r0, 0x10
//        0xe3a01cff, // mov r1, 0xFF00
//        0xe3a02003, // mov r2, 0x03
//      )

      for (_ <- 0 to 30) {
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
