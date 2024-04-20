package gba.cpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

class ARM7TDMISpec extends AnyFunSuite {
  test("basic") {
    simulate(new ARM7TDMI) { dut =>
      dut.io.enable.poke(true)

      for (_ <- 0 to 10) {
        dut.clock.step()
      }
    }
  }
}
