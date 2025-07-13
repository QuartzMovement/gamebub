package platform.handheld

import chiseltest._
import org.scalatest.freespec.AnyFreeSpec

class AudioDspTransmitterSpec extends AnyFreeSpec with ChiselScalatestTester {
  "go" in {
    test(new AudioDspTransmitter(
      bitWidth = 16,
      mclkFactor = 544,
      channels = 2,
    )) { dut =>
      dut.clock.setTimeout(10000)
      dut.io.dataLeft.poke(0xFFFF)
      dut.io.dataRight.poke(0xAAAA)
      for (i <- 0 until 4) {
        for (j <- 0 until 544) {
          if (dut.io.signals.mclk.peekBoolean()) {
            print("M")
          } else {
            print(" ")
          }
          if (dut.io.signals.wclk.peekBoolean()) {
            print("W")
          } else {
            print(" ")
          }
          if (dut.io.signals.bclk.peekBoolean()) {
            print("B")
          } else {
            print(" ")
          }
          if (dut.io.signals.data.peekInt() == 1) {
            print("*")
          } else {
            print(" ")
          }
          println()
          dut.clock.step()
        }
        println("------------------------------------------")
      }
    }
  }
}
