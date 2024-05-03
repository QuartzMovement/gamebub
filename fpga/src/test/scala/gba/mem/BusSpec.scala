package gba.mem

import chisel3._
import gba.cpu.{BusAccessWidth, BusTransactionType}
import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

class BusSpec extends AnyFunSuite {
  private def makeBus(): Bus = {
    new Bus(Seq(
      BusTarget("TargetA", 1.U(4.W), BusAccessWidth.Word),
      BusTarget("TargetB", 2.U(4.W), BusAccessWidth.Word),
      BusTarget("TargetC", 3.U(4.W), BusAccessWidth.Halfword),
    ))
  }

  test("simple read") {
    simulate(makeBus()) { dut =>
      dut.io.enable.poke(true)
      dut.io.initiatorPort.TRANS.poke(BusTransactionType.Internal)
      for (i <- 0 until 3) {
        dut.io.targetPort(i).done.poke(false)
      }

      // No requests at the start
      assert(!dut.io.targetPort(0).request.peek().litToBoolean)
      assert(!dut.io.targetPort(1).request.peek().litToBoolean)
      assert(!dut.io.targetPort(2).request.peek().litToBoolean)

      // Start a read from TargetA
      dut.io.initiatorPort.ADDR.poke(0x01_00ABC0)
      dut.io.initiatorPort.WRITE.poke(false)
      dut.io.initiatorPort.SIZE.poke(BusAccessWidth.Word)
      dut.io.initiatorPort.TRANS.poke(BusTransactionType.NonSequential)

      // Check that TargetA has a request
      assert(dut.io.targetPort(0).request.peek().litToBoolean)
      assert((dut.io.targetPort(0).address.peek().litValue & 0xFFFFFF) == 0x00ABC0)
      assert(!dut.io.targetPort(0).write.peek().litToBoolean)
      // (and *only* TargetA)
      assert(!dut.io.targetPort(1).request.peek().litToBoolean)
      assert(!dut.io.targetPort(2).request.peek().litToBoolean)

      // Clock, check that the request is not satisfied.
      dut.clock.step()
      assert(!dut.io.initiatorPort.CLKEN.peek().litToBoolean)

      // Mark the request as done:
      dut.io.targetPort(0).dataRead.poke(0xABCD1234)
      dut.io.targetPort(0).done.poke(true)
      assert(dut.io.initiatorPort.CLKEN.peek().litToBoolean)
      assert(dut.io.initiatorPort.RDATA.peek().litValue === 0xABCD1234L)

      // Make sure another request isn't happening next cycle.
      dut.io.initiatorPort.TRANS.poke(BusTransactionType.Internal)
      dut.clock.step()

      // Bus should be free.
      assert(dut.io.initiatorPort.CLKEN.peek().litToBoolean)
    }
  }
}
