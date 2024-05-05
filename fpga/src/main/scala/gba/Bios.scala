package gba

import chisel3._
import chisel3.util._
import gba.cpu.BusAccessWidth
import gba.mem.TargetInterface

class Bios extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val target = new TargetInterface(BusAccessWidth.Word)
  })

  val data = VecInit(Seq(
    0xe329f0dfL, // 0x0000: msr cpsr, #0xDF
    0xe3a0d403L, // 0x0004: mov sp, #0x03000000
    0xe38ddc7fL, // 0x0008: orr sp, #0x7F00
    0xe3a0f302L, // 0x000c: mov pc, #0x08000000
  ).map(x => x.U(32.W)))

  io.target.done := true.B

  val readAddress = Reg(UInt(14.W)) // 16 KiB
  when (io.enable) {
    readAddress := io.target.address
  }
  when (readAddress < (data.size * 4).U) {
    val address = Wire(UInt(log2Ceil(data.size).W))
    address := readAddress >> 2
    io.target.dataRead := data(address)
  } .otherwise {
    io.target.dataRead := "hFFFFFFFF".U(32.W)
  }
}
