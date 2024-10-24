package gba.link

import chisel3._
import chisel3.util._
import gba.{MmioMap, MmioTarget}
import lib.log.Logger

class Link extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val mmio = new MmioTarget()
    val irq = Output(Bool())
  })
  val logger = Logger("link", enable = io.enable)

  val regRcnt = RegInit(0.U(16.W))
  val regSiocnt = RegInit(0.U(15.W))
  val regSiodata8 = RegInit(0.U(16.W))

  io.irq := false.B
  io.mmio <> MmioMap(
    // SIOCNT
    0x128 -> MmioMap.Entry.rw16(regSiocnt, regSiodata8),
    // RCNT
    0x134 -> MmioMap.Entry.rw(regRcnt),
  )

  val regTimer = Reg(UInt(11.W))
  val regActive = RegInit(false.B)

  // TODO: this is stubbed
  when (io.enable) {
    when (!regActive && regSiocnt(7)) {
      logger.info("SIO activate")
      regActive := true.B

      val clockFast = regSiocnt(1)
      val transfer32 = regSiocnt(12)
      when (clockFast) {
        regTimer := Mux(transfer32, 255.U, 63.U)
      } .otherwise {
        regTimer := Mux(transfer32, 2047.U, 511.U)
      }
    }

    when (regActive) {
      regTimer := regTimer - 1.U
      when (regTimer === 0.U) {
        logger.info("SIO complete")
        regActive := false.B
        val irqEnabled = regSiocnt(14)
        when (irqEnabled) {
          io.irq := true.B
        }
        // Unset active bit.
        regSiocnt := Cat(regSiocnt(14, 8), 0.U(1.W), regSiocnt(6, 0))
      }
    }
  }
}