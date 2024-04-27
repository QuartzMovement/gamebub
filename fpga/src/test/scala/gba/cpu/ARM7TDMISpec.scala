package gba.cpu

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
      // TODO support stores (8, 16, or 32 bit)
      // Note: addresses are not necessarily aligned, they are aligned by memory controller.

      if (memTrans == BusTransactionType.Sequential.litValue || memTrans == BusTransactionType.NonSequential.litValue) {
        val seq = if (memTrans == BusTransactionType.Sequential.litValue) "   Seq" else "NonSeq"
        if (memWrite) {
          val memDataWrite = dut.io.mem.WDATA.peek().litValue
          System.err.println(f"Mem Write $seq: [0x$memAddress%X] <- 0x$memDataWrite%X | size=$memSize\n")
          // TODO!
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
      assert(dut.io.mem.ADDR.peek().litValue == address, "read address")
      assert(!dut.io.mem.WRITE.peek().litToBoolean, "not read")
      assert(dut.io.mem.TRANS.peekValue().asBigInt == trans.litValue, "wrong trans")
    }

    def assertMemWrite(address: Int, trans: BusTransactionType.Type): Unit = {
      assert(dut.io.mem.ADDR.peek().litValue == address, "write address")
      assert(dut.io.mem.WRITE.peek().litToBoolean, "not write")
      assert(dut.io.mem.TRANS.peekValue().asBigInt == trans.litValue, "wrong trans")
    }

    def memWriteData(): Int = {
      dut.io.mem.WDATA.peek().litValue.toInt
    }

    def reg(index: Int): Int = {
      dut.io.debug.registers.getElements(index).peekValue().asBigInt.toInt
    }

    def cpsr(): Int = {
      dut.io.debug.cpsr.peek().litValue.toInt
    }

    def cpsr_flags(): Int = {
      (cpsr() >> 28) & 0xF
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
        0xe3a02006, // 0x0018: mov r2, 6
        0xe3a02007, // 0x001C: mov r2, 7
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

      cpu.step()
      assert(cpu.reg(2) == 6)

      cpu.step()
      assert(cpu.reg(2) == 7)
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

  def testLoad(
                dut: ARM7TDMI,
                instruction: Int,
                address: Option[Int] = None,
                data: Int,
                base: Option[Int] = None
              ): Unit = {
    val cpu = new CpuHarness(dut)
    cpu.copyMem(Array(
      0xe3a00ffa, // 0x0000: mov r0, #1000
      0xe3a04004, // 0x0004: mov r4, #4
      instruction,
      0xe3a02001, // 0x000C: mov r2, #1
      0xe3a02002, // 0x0010: mov r2, #2
      0xe3a02003, // 0x0014: mov r2, #3
    ))
    cpu.copyMem(Array(0xAABBCCDD, 0x11223344, 0x55667788), 996)
    cpu.step(3)
    cpu.step()
    assert(cpu.reg(0) == 1000)
    cpu.step()

    // Load: compute address
    if (address.isDefined) {
      cpu.assertMemRead(address.get, BusTransactionType.NonSequential)
    }
    // TODO: assert prot0 is 1(?) for data
    cpu.step()

    // Load: register writeback
    cpu.assertMemRead(20, BusTransactionType.Internal)
    cpu.step()
    if (base.isDefined) {
      assert(cpu.reg(0) == base.get)
    }

    // Load: save the memory
    cpu.assertMemRead(20, BusTransactionType.Sequential)
    cpu.step()
    assert(cpu.reg(1) == data)

    cpu.step()
    assert(cpu.reg(2) == 1)

    cpu.step()
    assert(cpu.reg(2) == 2)

    cpu.step()
    assert(cpu.reg(2) == 3)
  }

  test("load") {
    simulate(new ARM7TDMI) { dut =>
      // Load word with various addressing modes.
      testLoad(dut,
        instruction = 0xe5901000, // ldr r1, [r0]
        address = Some(1000),
        data = 0x11223344,
        base = Some(1000),
      )
      testLoad(dut,
        instruction = 0xe5901004, // ldr r1, [r0, #4]
        address = Some(1004),
        data = 0x55667788,
        base = Some(1000),
      )
      testLoad(dut,
        instruction = 0xe5b01004, // ldr r1, [r0, #4]!
        address = Some(1004),
        data = 0x55667788,
        base = Some(1004),
      )
      testLoad(dut,
        instruction = 0xe5301004, // ldr r1, [r0, #-4]!
        address = Some(996),
        data = 0xAABBCCDD,
        base = Some(996),
      )
      testLoad(dut,
        instruction = 0xe4901004, // ldr r1, [r0], #4
        address = Some(1000),
        data = 0x11223344,
        base = Some(1004),
      )
      testLoad(dut,
        instruction = 0xe7901184, // ldr r1, [r0, r4, LSL #3]
        address = Some(1032),
        data = 0x0,
        base = Some(1000),
      )

      // Load byte unsigned
      testLoad(dut,
        instruction = 0xe5d01000, // ldrb r1, [r0, #0]
        data = 0x44)
      testLoad(dut,
        instruction = 0xe5d01001, // ldrb r1, [r0, #1]
        data = 0x33)
      testLoad(dut,
        instruction = 0xe5d01002, // ldrb r1, [r0, #2]
        data = 0x22)
      testLoad(dut,
        instruction = 0xe5d01003, // ldrb r1, [r0, #3]
        data = 0x11)

      // Load byte signed
      testLoad(dut,
        instruction = 0xe1d010d0, // ldrsb r1, [r0, #0]
        data = 0x44)
      testLoad(dut,
        instruction = 0xe1d010d1, // ldrsb r1, [r0, #1]
        data = 0x33)
      testLoad(dut,
        instruction = 0xe1d010d2, // ldrsb r1, [r0, #2]
        data = 0x22)
      testLoad(dut,
        instruction = 0xe1d010d3, // ldrsb r1, [r0, #3]
        data = 0x11)
      testLoad(dut,
        instruction = 0xe15010d4, // ldrsb r1, [r0, #-4]
        data = 0xFFFFFFDD)
      testLoad(dut,
        instruction = 0xe15010d3, // ldrsb r1, [r0, #-3]
        data = 0xFFFFFFCC)
      testLoad(dut,
        instruction = 0xe15010d2, // ldrsb r1, [r0, #-2]
        data = 0xFFFFFFBB)
      testLoad(dut,
        instruction = 0xe15010d1, // ldrsb r1, [r0, #-1]
        data = 0xFFFFFFAA)

      // Load halfword unsigned
      testLoad(dut,
        instruction = 0xe1d010b0, // ldrh r1, [r0, #0]
        data = 0x3344)
      testLoad(dut,
        instruction = 0xe1d010b1, // ldrh r1, [r0, #0]
        data = 0x44000033)
      testLoad(dut,
        instruction = 0xe1d010b2, // ldrh r1, [r0, #2]
        data = 0x1122)
      testLoad(dut,
        instruction = 0xe1d010b3, // ldrh r1, [r0, #0]
        data = 0x22000011)

      // Load halfword signed
      testLoad(dut,
        instruction = 0xe1d010f0, // ldrsh r1, [r0, #0]
        data = 0x3344)
      testLoad(dut,
        instruction = 0xe1d010f1, // ldrsh r1, [r0, #1]
        data = 0x33)
      testLoad(dut,
        instruction = 0xe1d010f2, // ldrsh r1, [r0, #2]
        data = 0x1122)
      testLoad(dut,
        instruction = 0xe1d010f3, // ldrsh r1, [r0, #3]
        data = 0x11)
      testLoad(dut,
        instruction = 0xe15010f4, // ldrsh r1, [r0, #-4]
        data = 0xFFFFCCDD)
      testLoad(dut,
        instruction = 0xe15010f3, // ldrsh r1, [r0, #-3]
        data = 0xFFFFFFCC)
      testLoad(dut,
        instruction = 0xe15010f2, // ldrsh r1, [r0, #-2]
        data = 0xFFFFAABB)
      testLoad(dut,
        instruction = 0xe15010f1, // ldrsh r1, [r0, #-1]
        data = 0xFFFFFFAA)
    }
  }

  def testStore(
                dut: ARM7TDMI,
                instruction: Int,
                address: Option[Int] = None,
                data: Int,
                base: Option[Int] = None
              ): Unit = {
    val cpu = new CpuHarness(dut)
    cpu.copyMem(Array(
      0xe3a00ffa, // 0x0000: mov r0, #1000
      0xe3a01011, // 0x0004: mov r1, #0x11
      0xe3811c22, // 0x0008: orr r1, r1, #0x2200
      0xe3811833, // 0x000c: orr r1, r1, #0x330000
      0xe3811311, // 0x0010: orr r1, r1, #0x44000000
      0xe3a04004, // 0x0014: mov r4, #4
      instruction,
      0xe3a02001, // 0x001c: mov r2, #1
      0xe3a02002, // 0x0020: mov r2, #2
      0xe3a02003, // 0x0024: mov r2, #3
    ))
    cpu.copyMem(Array(0xAABBCCDD, 0x99887766, 0x55667788), 996)
    cpu.step(3)
    cpu.step(5)
    assert(cpu.reg(1) == 0x44332211)
    cpu.step()

    // Store: compute address
    if (address.isDefined) {
      cpu.assertMemWrite(address.get, BusTransactionType.NonSequential)
    }
    // TODO: assert prot0 is 1(?) for data
    cpu.step()
    assert(cpu.memWriteData() == data)

    // Store: base modification
    cpu.assertMemRead(36, BusTransactionType.NonSequential)
    cpu.step()
    if (base.isDefined) {
      assert(cpu.reg(0) == base.get)
    }

    cpu.step()
    assert(cpu.reg(2) == 1)

    cpu.step()
    assert(cpu.reg(2) == 2)

    cpu.step()
    assert(cpu.reg(2) == 3)
  }

  test("store") {
    simulate(new ARM7TDMI) { dut =>
      testStore(dut,
        instruction = 0xe5801000, // str r1, [r0]
        address = Some(1000),
        data = 0x44332211,
        base = Some(1000),
      )

      testStore(dut,
        instruction = 0xE5A00004, // str r0, [r0, #4]!
        address = Some(1004),
        data = 1000,
        base = Some(1004),
      )

      testStore(dut,
        instruction = 0xe5c01000, // strb r1, [r0]
        data = 0x11111111,
      )

      testStore(dut,
        instruction = 0xe1c010b0, // strh r1, [r0]
        data = 0x22112211,
      )
    }
  }

  test("swap") {
    simulate(new ARM7TDMI) { dut =>
      def testSwap(instruction: Int, rd: Int, storeData: Int, loadData: Int): Unit = {
        val cpu = new CpuHarness(dut)
        cpu.copyMem(Array(
          0xe3a00ffa, // 0x0000: mov r0, #1000
          0xe3a01011, // 0x0004: mov r1, #0x11
          0xe3811c22, // 0x0008: orr r1, r1, #0x2200
          0xe3811833, // 0x000c: orr r1, r1, #0x330000
          0xe3811311, // 0x0010: orr r1, r1, #0x44000000
          instruction,
          0xe3a02001, // 0x0018: mov r2, #1
          0xe3a02002, // 0x001c: mov r2, #2
          0xe3a02003, // 0x0020: mov r2, #3
        ))
        cpu.copyMem(Array(0xAABBCCDD), 1000)
        cpu.step(3)
        cpu.step(5)

        // Swap: load
        // TODO assert "LOCK" is set (and PROT is data)
        cpu.assertMemRead(1000, BusTransactionType.NonSequential)
        cpu.step()

        // Swap: store
        // TODO assert "LOCK" is set (and PROT is data)
        cpu.assertMemWrite(1000, BusTransactionType.NonSequential)
        cpu.step()
        assert(cpu.memWriteData() == storeData)

        // Swap: write-back to register
        cpu.assertMemRead(0x20 /* pc + 12 */ , BusTransactionType.Internal)
        cpu.step()

        // Swap: prefetch?
        cpu.assertMemRead(0x20 /* pc + 12 */ , BusTransactionType.Sequential)
        cpu.step()
        assert(cpu.reg(rd) == loadData)

        cpu.step()
        assert(cpu.reg(2) == 1)
        cpu.step()
        assert(cpu.reg(2) == 2)
        cpu.step()
        assert(cpu.reg(2) == 3)
      }

      testSwap(
        0xe1002091, // swp r2, r1, [r0]
        rd = 2,
        storeData = 0x44332211,
        loadData = 0xAABBCCDD,
      )

      testSwap(
        0xe1001091, // swp r1, r1, [r0]
        rd = 1,
        storeData = 0x44332211,
        loadData = 0xAABBCCDD,
      )

      testSwap(
        0xe1402091, // swp r2, r1, [r0]
        rd = 2,
        storeData = 0x11111111,
        loadData = 0xDD,
      )
    }
  }

  test("branch") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xEB0003FE, // 0x0000: bl +0x1000
        0xe3a01001, // 0x0004: mov r1, #1
      ))
      cpu.copyMem(Array(
        0xe3a02001, // 0x1000: mov r2, #1
        0xe3a02002, // 0x1004: mov r2, #2
        0xe3a02003, // 0x1008: mov r2, #3
      ), 0x1000)
      cpu.step(3)

      // Branch 1: load from branch target
      cpu.assertMemRead(0x1000, BusTransactionType.NonSequential)
      cpu.step()

      // Branch 2: refill pipeline
      cpu.assertMemRead(0x1004, BusTransactionType.Sequential)
      cpu.step()

      // Branch 3: refill pipeline
      cpu.assertMemRead(0x1008, BusTransactionType.Sequential)
      cpu.step()

      // Check that link flag and PC were set correctly.
      assert(cpu.reg(14) == 0x4)
      assert(cpu.reg(15) == 0x1008)

      cpu.step()
      assert(cpu.reg(2) == 1)
      cpu.step()
      assert(cpu.reg(2) == 2)
      cpu.step()
      assert(cpu.reg(2) == 3)
    }
  }

  test("branch exchange") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe3a0000c, // 0x0000: mov r0, #0xC
        0xe12fff10, // 0x0004: bx r0
        0xe3a01001, // 0x0008: mov r1, #1
        0xe3a00019, // 0x000c: mov r0, #0x19
        0xe12fff10, // 0x0010: bx r0
        0xe3a01002, // 0x0014: mov r1, #2
      ))
      cpu.step(3)
      cpu.step()

      // Branch 1
      cpu.assertMemRead(0xC, BusTransactionType.NonSequential)
      cpu.step(3)
      assert((cpu.cpsr() & 0x20) == 0)

      cpu.step(1)
      assert(cpu.reg(1) == 0)

      // Branch 2
      cpu.assertMemRead(0x18, BusTransactionType.NonSequential)
      cpu.step(3)
      assert((cpu.cpsr() & 0x20) != 0)

      // TODO: check more once Thumb is implemented
    }
  }

  test("move to/from cpsr") {
    simulate(new ARM7TDMI) { dut =>
      val cpu = new CpuHarness(dut)
      cpu.copyMem(Array(
        0xe1500000, // 0x0000: cmp r0, r0
        0xe10f1000, // 0x0004: mrs r1, cpsr
        0xe328f203, // 0x0008: msr cpsr_f, #0x30000000
        0x63822001, // 0x000c: orrvs r2, #1
        0x23822002, // 0x0010: orrcs r2, #2
        0x03822004, // 0x0014: orreq r2, #4
        0x43822008, // 0x0018: orrmi r2, #8
        0xe3a03209, // 0x001c: mov r3, #0x90000000
        0xe128f003, // 0x0020: msr cpsr_f, r3
        0x63844001, // 0x0024: orrvs r4, #1
        0x23844002, // 0x0028: orrcs r4, #2
        0x03844004, // 0x002c: orreq r4, #4
        0x43844008, // 0x0030: orrmi r4, #8
      ))
      cpu.step(3)

      cpu.step()

      // MRS
      cpu.step()
      assert(cpu.reg(1) == 0x400000DF) // Note: last 'F' means system mode

      // MSR (immediate)
      cpu.step(5)
      assert(cpu.reg(2) == 3)

      // MSR (reg)
      cpu.step(6)
      assert(cpu.reg(4) == 9)
    }
  }
}
