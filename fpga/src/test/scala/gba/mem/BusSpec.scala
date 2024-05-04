package gba.mem

import chisel3._
import gba.cpu.{BusAccessWidth, BusTransactionType}
import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

case class TargetAccess(
  address: BigInt,
  write: Boolean = false,
  sequential: Boolean = false,
)

class BusSpec extends AnyFunSuite {
  private def makeBus(): Bus = {
    new Bus(Seq(
      BusTarget("TargetA", 1.U(4.W), BusAccessWidth.Word),
      BusTarget("TargetB", 2.U(4.W), BusAccessWidth.Word),
      BusTarget("TargetC", 3.U(4.W), BusAccessWidth.Halfword),
    ))
  }

  private class BusHarness(dut: Bus) {
    val targets = 3

    dut.io.enable.poke(true)
    dut.io.initiatorPort.TRANS.poke(BusTransactionType.Internal)
    for (i <- 0 until targets) {
      dut.io.targetPort(i).done.poke(false)
    }

    def setAccess(
      address: Int,
      size: BusAccessWidth.Type = BusAccessWidth.Word,
      write: Boolean = false,
      sequential: Boolean = false): Unit = {
      dut.io.initiatorPort.ADDR.poke(address)
      dut.io.initiatorPort.WRITE.poke(write)
      dut.io.initiatorPort.SIZE.poke(size)
      dut.io.initiatorPort.TRANS.poke(
        if (sequential) { BusTransactionType.Sequential }
        else { BusTransactionType.NonSequential }
      )
    }

    def getReadData(): BigInt = {
      dut.io.initiatorPort.RDATA.peek().litValue
    }

    def getTargetAccess(i: Int): Option[TargetAccess] = {
      if (dut.io.targetPort(i).request.peek().litToBoolean) {
        val address = dut.io.targetPort(i).address.peek().litValue & 0xFFFFFF
        val write = dut.io.targetPort(i).write.peek().litToBoolean
        val sequential = dut.io.targetPort(i).sequential.peek().litToBoolean
        Some(TargetAccess(address, write, sequential))
      } else {
        None
      }
    }

    def setTargetDone(i: Int, dataRead: BigInt = 0): Unit = {
      dut.io.targetPort(i).dataRead.poke(dataRead)
      dut.io.targetPort(i).done.poke(true)
    }

    def getClockEn(): Boolean = {
      dut.io.initiatorPort.CLKEN.peek().litToBoolean
    }

    def step(): Unit = {
      dut.clock.step()

      // Reset some state so accesses don't continue by default
      dut.io.initiatorPort.TRANS.poke(BusTransactionType.Internal)
      for (i <- 0 until targets) {
        dut.io.targetPort(i).done.poke(false)
      }
    }
  }

  test("single-cycle read") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // No requests at the start
      assert(bus.getTargetAccess(0).isEmpty)
      assert(bus.getTargetAccess(1).isEmpty)
      assert(bus.getTargetAccess(2).isEmpty)

      // Start a read from TargetA
      bus.setAccess(address = 0x01_00ABC0)

      // Check that TargetA has a request
      assert(bus.getTargetAccess(0).contains(TargetAccess(0xABC0)))
      assert(bus.getTargetAccess(1).isEmpty)
      assert(bus.getTargetAccess(2).isEmpty)

      // Clock, check that the request is not satisfied.
      bus.step()
      assert(!bus.getClockEn())

      // Mark the request as done:
      bus.setTargetDone(0, 0xABCD1234)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0xABCD1234L)

      // Bus should be free next cycle.
      bus.step()
      assert(dut.io.initiatorPort.CLKEN.peek().litToBoolean)
    }
  }

  test("multi-cycle read") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // Start a read from TargetA
      bus.setAccess(address = 0x01_000000)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0)))

      // Clock a few times, check that the request is not satisfied.
      bus.step()
      assert(!bus.getClockEn())
      bus.step()
      assert(!bus.getClockEn())
      bus.step()
      assert(!bus.getClockEn())

      // Mark the request as done:
      bus.setTargetDone(0, 0xABCD1234)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0xABCD1234L)
    }
  }

  /// Repeated, pipelined single-cycle accesses on a single target
  test("repeated accesses") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // Start Read 1 from TargetB
      bus.setAccess(address = 0x02_000FF0)
      assert(bus.getTargetAccess(1).contains(TargetAccess(0xFF0)))
      assert(bus.getClockEn())
      bus.step()

      // Read 1, Start read 2
      bus.setTargetDone(1, 0x0001)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0x0001)
      bus.setAccess(address = 0x02_000FF4)
      assert(bus.getTargetAccess(1).contains(TargetAccess(0xFF4)))
      bus.step()

      // Read 2, Start read 3
      bus.setTargetDone(1, 0x0002)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0x0002)
      bus.setAccess(address = 0x02_000FF8)
      assert(bus.getTargetAccess(1).contains(TargetAccess(0xFF8)))
      bus.step()

      // Read 3
      bus.setTargetDone(1, 0x0003)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0x0003)
      bus.step()
    }
  }

  /// Addresses are force-aligned
  test("align addresses") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // Word
      bus.setAccess(address = 0x01_000000, size = BusAccessWidth.Word)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0)))
      bus.setAccess(address = 0x01_000001, size = BusAccessWidth.Word)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0)))
      bus.setAccess(address = 0x01_000002, size = BusAccessWidth.Word)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0)))
      bus.setAccess(address = 0x01_000003, size = BusAccessWidth.Word)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0)))
      bus.setAccess(address = 0x01_000004, size = BusAccessWidth.Word)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x4)))

      // Halfword
      bus.setAccess(address = 0x01_000000, size = BusAccessWidth.Halfword)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0)))
      bus.setAccess(address = 0x01_000001, size = BusAccessWidth.Halfword)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0)))
      bus.setAccess(address = 0x01_000002, size = BusAccessWidth.Halfword)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x2)))
      bus.setAccess(address = 0x01_000003, size = BusAccessWidth.Halfword)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x2)))
      bus.setAccess(address = 0x01_000004, size = BusAccessWidth.Halfword)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x4)))

      // Byte
      bus.setAccess(address = 0x01_000000, size = BusAccessWidth.Byte)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x0)))
      bus.setAccess(address = 0x01_000001, size = BusAccessWidth.Byte)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x1)))
      bus.setAccess(address = 0x01_000002, size = BusAccessWidth.Byte)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x2)))
      bus.setAccess(address = 0x01_000003, size = BusAccessWidth.Byte)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x3)))
      bus.setAccess(address = 0x01_000004, size = BusAccessWidth.Byte)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x4)))
    }
  }

  test("multiple targets") {
    simulate(makeBus()) { dut =>
      val bus = new BusHarness(dut)

      // Start Read 1 from TargetA
      bus.setAccess(address = 0x01_123000)
      assert(bus.getTargetAccess(0).contains(TargetAccess(0x123000)))
      assert(bus.getTargetAccess(1).isEmpty)
      assert(bus.getTargetAccess(2).isEmpty)
      assert(bus.getClockEn())
      bus.step()

      // Read 1, Start read 2
      assert(!bus.getClockEn())
      bus.setTargetDone(0, 0x0001)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0x0001)
      bus.setAccess(address = 0x02_ABC000)
      assert(bus.getTargetAccess(0).isEmpty)
      assert(bus.getTargetAccess(1).contains(TargetAccess(0xABC000)))
      assert(bus.getTargetAccess(2).isEmpty)
      bus.step()

      // Read 2, Start read 3
      bus.setTargetDone(1, 0x0002)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0x0002)
      bus.setAccess(address = 0x03_DEF000)
      assert(bus.getTargetAccess(0).isEmpty)
      assert(bus.getTargetAccess(1).isEmpty)
      assert(bus.getTargetAccess(2).contains(TargetAccess(0xDEF000)))
      bus.step()

      // Read 3
      bus.setTargetDone(2, 0x0003)
      assert(bus.getClockEn())
      assert(bus.getReadData() === 0x0003)
      bus.step()
    }
  }
}
