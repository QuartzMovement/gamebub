package gameboy

import chisel3._
import chisel3.util._
import gameboy.Gameboy.Model
import gameboy.util.MemRomTable

class BootRomAccess extends Bundle {
  val read = Output(Bool())
  val address = Output(UInt(11.W))
  val data = Input(UInt(8.W))
}

class BootRom(config: Gameboy.Configuration) extends Module {
  val io = IO(new Bundle {
    val address = Input(UInt(16.W))
    val valid = Output(Bool())
    val dataRead = Output(UInt(8.W))

    /** True if the bootrom is mapped. */
    val mapped = Input(Bool())

    /** Access to externally stored bootrom */
    val access = new BootRomAccess
  })

  io.valid := false.B
  io.dataRead := io.access.data
  io.access.address := DontCare
  io.access.read := io.valid
  when (io.mapped) {
    when (io.address < 0x100.U) {
      io.access.address := io.address
      io.valid := true.B
    }

    if (config.model.isCgb) {
      when (io.address >= 0x200.U && io.address < 0x900.U) {
        io.access.address := io.address - 0x100.U
        io.valid := true.B
      }
    }
  }
}