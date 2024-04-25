package gba.cpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

class ARM7TDMISpec extends AnyFunSuite {
  /// CPU harness providing memory accesses
  class CpuHarness(dut: ARM7TDMI) {
    reset()

    // Array of 32-bit integers for memory
    private val mem = Array.fill(1 * 1024 * 1024)(0)

    def reset(): Unit = {
      dut.io.enable.poke(true)
      dut.io.mem.RDATA.poke(0xFFFFFFFF)
      dut.reset.poke(true)
      dut.clock.step()
      dut.reset.poke(false)
    }

    def copyMem(data: Array[Int], address: Int = 0): Unit = {
      data.copyToArray(mem, address / 4)
    }

    def step(): Unit = {
      val memAddress = dut.io.mem.ADDR.peek().litValue
      val memWrite = dut.io.mem.WRITE.peek().litToBoolean
      val memSize = 1 << dut.io.mem.SIZE.peekValue().asBigInt.toInt
      val memTrans = dut.io.mem.TRANS.peekValue().asBigInt

      dut.clock.step()

      // TODO verify bursts are valid
      // TODO support non-32-bit load/store
      // TODO support stores
      // TODO verify addresses are aligned properly

      if (memTrans == BusTransactionType.Sequential.litValue || memTrans == BusTransactionType.NonSequential.litValue) {
        val seq = if (memTrans == BusTransactionType.Sequential.litValue) "   Seq" else "NonSeq"
        if (memWrite) {
          val memDataWrite = dut.io.mem.WDATA.peek().litValue
          System.err.println(f"Mem Write $seq: [0x$memAddress%X] <- 0x$memDataWrite%X | size=$memSize\n")
        } else {
          val readData = mem.lift(memAddress.toInt >> 2).getOrElse(0xffffffff)
          dut.io.mem.RDATA.poke(readData)
          System.err.println(f"Mem  Read $seq: [0x$memAddress%X] -> 0x$readData%X | size=$memSize\n")
        }
      }
      if (memTrans == BusTransactionType.Internal.litValue) {
        System.err.println(f"Mem          Int: [0x$memAddress%X]\n")
        dut.io.mem.RDATA.poke(0xffffffff)
      }
    }

    def step(cycles: Int): Unit = {
      for (_ <- 0 until cycles) {
        step()
      }
    }

    def assertMemRead(address: Int, trans: BusTransactionType.Type): Unit = {
      assert(dut.io.mem.ADDR.peek().litValue == address)
      assert(!dut.io.mem.WRITE.peek().litToBoolean)
      assert(dut.io.mem.TRANS.peekValue().asBigInt == trans.litValue)
    }

    def reg(index: Int): Int = {
      dut.io.debug.registers.getElements(index).peekValue().asBigInt.toInt
    }

    def cpsr_flags(): Int = {
      (dut.io.debug.cpsr.cond.n.peekValue().asBigInt.toInt << 3) |
        (dut.io.debug.cpsr.cond.z.peekValue().asBigInt.toInt << 2) |
        (dut.io.debug.cpsr.cond.c.peekValue().asBigInt.toInt << 1) |
        (dut.io.debug.cpsr.cond.v.peekValue().asBigInt.toInt << 0)
    }
  }

  test("reset") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe3a00001, // 0x0000: mov r0, 1
        0xe3a01002, // 0x0004: mov r1, 2
        0xe3a02003, // 0x0008: mov r2, 3
      ))

      // TODO: first read should be NonSequential
      cpu.assertMemRead(0x00, BusTransactionType.Sequential)
      cpu.step()
      assert(cpu.reg(15) == 0x0)
      cpu.assertMemRead(0x04, BusTransactionType.Sequential)
      cpu.step()
      assert(cpu.reg(15) == 0x4)
      cpu.assertMemRead(0x08, BusTransactionType.Sequential)
      cpu.step()
      assert(cpu.reg(15) == 0x8)
      assert(cpu.reg(0) == 0x0)
      cpu.assertMemRead(0x0C, BusTransactionType.Sequential)
      cpu.step()
      assert(cpu.reg(15) == 0xC)
      assert(cpu.reg(0) == 0x1)
    }
  }

  test("data processing: immediate") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe3b00001, // 0x0000: movs r0, 1
        0xe2801004, // 0x0004: add r1, r0, 4
        0xe2412005, // 0x0008: sub r2, r1, 5
        0xe2512005, // 0x000c: subs r2, r1, 5
      ))
      cpu.step(3)

      cpu.step()
      assert(cpu.reg(0) == 1)
      assert((cpu.cpsr_flags() & 4) == 0) // Z flag

      cpu.step()
      assert(cpu.reg(1) == 5)

      cpu.step()
      assert(cpu.reg(2) == 0)
      assert((cpu.cpsr_flags() & 4) == 0) // Z flag

      cpu.step()
      assert((cpu.cpsr_flags() & 4) == 4) // Z flag
    }
  }

  test("data processing: branch") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe3a0f014, // 0x0000: mov pc, 20
        0xe3a01001, // 0x0004: mov r1, 1
        0xe3a01002, // 0x0008: mov r1, 2
        0xe3a01003, // 0x000C: mov r1, 3
        0xe3a01004, // 0x0010: mov r1, 4
        0xe3a02005, // 0x0014: mov r2, 5
      ))

      cpu.assertMemRead(0, BusTransactionType.Sequential)
      cpu.step()
      cpu.assertMemRead(4, BusTransactionType.Sequential)
      cpu.step()
      cpu.assertMemRead(8, BusTransactionType.Sequential)
      cpu.step()
      cpu.assertMemRead(20, BusTransactionType.NonSequential)

      cpu.step()
      assert(cpu.reg(15) == 20)
      cpu.assertMemRead(24, BusTransactionType.Sequential)

      cpu.step()
      assert(cpu.reg(15) == 24)
      cpu.assertMemRead(28, BusTransactionType.Sequential)

      cpu.step()
      assert(cpu.reg(15) == 28)
      assert(cpu.reg(2) == 0)
      cpu.assertMemRead(32, BusTransactionType.Sequential)

      cpu.step()
      assert(cpu.reg(1) == 0)
      assert(cpu.reg(2) == 5)
      cpu.assertMemRead(36, BusTransactionType.Sequential)
    }
  }

  test("data processing: shift by register") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe3a0000c, // 0x0000: mov r0, 12
        0xe3a01cff, // 0x0004: mov r1, 0xFF00
        0xe0812011, // 0x0008: add r2, r1, r1, LSL r0
      ))

      cpu.step(3)

      // mov r0, 12
      cpu.step()
      assert(cpu.reg(0) == 12)
      // mov r1, 0xFF00
      cpu.step()

      // add ...
      cpu.assertMemRead(20, BusTransactionType.Internal)
      cpu.step()
      cpu.assertMemRead(20, BusTransactionType.Sequential)
      cpu.step()
      assert(cpu.reg(2) == 0xFF0FF00)

      // next
      cpu.assertMemRead(24, BusTransactionType.Sequential)
    }
  }

  def testLoad(instruction: Int, address: Int, base: Int): Unit = {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe3a00ffa, // 0x0000: mov r0, #1000
        0xe3a04004, // 0x0004: mov r4, #4
        instruction,
        0xe3a02001, // 0x000C: mov r2, 1
      ))
      cpu.copyMem(Array(0xAABBCCDD, 0x11223344, 0x55667788), 996)
      cpu.step(3)
      cpu.step()
      assert(cpu.reg(0) == 1000)
      cpu.step()

      // Load: compute address
      cpu.assertMemRead(address, BusTransactionType.NonSequential)
      // TODO: assert prot0 is 1(?) for data
      cpu.step()

      // Load: register writeback
      cpu.assertMemRead(16, BusTransactionType.Internal)
      cpu.step()
      assert(cpu.reg(0) == base)

      // Load: save the memory
      cpu.assertMemRead(16, BusTransactionType.Sequential)
      cpu.step()
      address match {
        case 996 => assert(cpu.reg(1) == 0xAABBCCDD)
        case 1000 => assert(cpu.reg(1) == 0x11223344)
        case 1004 => assert(cpu.reg(1) == 0x55667788)
        case _ =>
      }


      cpu.step()
      assert(cpu.reg(2) == 1)
    }
  }

  test("load") {
    testLoad(
      instruction = 0xe5901000, // ldr r1, [r0]
      address = 1000,
      base = 1000,
    )

    testLoad(
      instruction = 0xe5901004, // ldr r1, [r0, #4]
      address = 1004,
      base = 1000,
    )

    testLoad(
      instruction = 0xe5b01004, // ldr r1, [r0, #4]!
      address = 1004,
      base = 1004,
    )

    testLoad(
      instruction = 0xe5301004, // ldr r1, [r0, #-4]!
      address = 996,
      base = 996,
    )

    testLoad(
      instruction = 0xe4901004, // ldr r1, [r0], #4
      address = 1000,
      base = 1004,
    )

    testLoad(
      instruction = 0xe7901184, // ldr r1, [r0, r4, LSL #3]
      address = 1032,
      base = 1000,
    )
  }
}
