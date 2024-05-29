package gba.ppu

import chisel3._
import chisel3.util._

class ObjectAttribute0 extends Bundle {
  val shape = UInt(2.W)
  val bpp8 = Bool()
  val mosaic = Bool()
  val effect = UInt(2.W)
  val double = Bool()
  val affine = Bool()
  val y = UInt(8.W)
}

class ObjectAttribute1 extends Bundle {
  val size = UInt(2.W)
  val flipY = Bool()
  val flipX = Bool()
  val affineIndexLo = UInt(3.W)
  val x = UInt(9.W)
}

class ObjectAttribute2 extends Bundle {
  val paletteBank = UInt(4.W)
  val priority = UInt(2.W)
  val tile = UInt(10.W)
}

class ObjectBufferEntry extends Bundle {
  val valid = Bool()
  val color = UInt(8.W)
  val priority = UInt(2.W)
}

class ObjectRenderer extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    val displayControl = Input(new PpuRegisters.DisplayControl)

    /// OBJ VRAM access
    val vram = Flipped(new PpuMemoryInterface(32 * 1024 / 2, 16.W))

    /// OAM access
    val oam = Flipped(new PpuMemoryInterface(1024 / 4, 32.W))

    /// Current cycle in the scanline
    val tick = Input(UInt(11.W))
    val scanline = Input(UInt(8.W))

    /// Compositor access of buffer
    val bufferIndex = Input(UInt(8.W))
    val bufferRead = Input(Bool())
    val bufferData = Output(new ObjectBufferEntry)
  })

  val active = RegInit(false.B)
  val renderY = Reg(UInt(8.W))

  // Object scanline buffer. 240 entries, times two, rounded to power-of-two.
  val buffer = SyncReadMem(512, UInt((new ObjectBufferEntry).getWidth.W))
  val bufferWriteIndex = Wire(UInt(8.W))
  val bufferWriteData = Wire(new ObjectBufferEntry)
  val bufferWriteEnable = WireDefault(false.B)
  val bufferPage = Reg(UInt(1.W))
  bufferWriteIndex := DontCare
  bufferWriteData := DontCare
  io.bufferData := buffer.read(
    Cat(!bufferPage, io.bufferIndex),
    io.bufferRead && io.enable
  ).asTypeOf(new ObjectBufferEntry)
  when (bufferWriteEnable && io.enable) {
    buffer.write(Cat(bufferPage, io.bufferIndex), bufferWriteData.asUInt)
  }

  // Object render activation
  when (io.enable) {
    when (active && io.tick === Mux(io.displayControl.hblankFree, 1005.U, 39.U)) {
//      printf(cf"[${io.scanline} | ${io.tick}] obj done (for ${renderY})\n")
      active := false.B
    }
    when (io.displayControl.enableObj && (io.scanline < 160.U || io.scanline === 227.U) && io.tick === 39.U) {
//      printf(cf"[${io.scanline} | ${io.tick}] obj activate\n")
      active := true.B
      renderY := io.scanline + 1.U
      when (io.scanline === 227.U) {
        renderY := 0.U
      }
      bufferPage := !bufferPage
      // TODO reset other state
    }
  }


  io.vram.read := false.B
  io.vram.address := DontCare

  io.oam.read := false.B
  io.oam.address := DontCare
}
