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

/// Combined and calculated object attributes from the OAM fetch stage.
class ObjectAttributeFull extends Bundle {
  val x = UInt(9.W)
  /// Pixel row within the object
  val row = UInt(6.W)
  /// Width in tiles (8 pixels)
  val w = UInt(4.W)
  /// Height in tiles (8 pixels)
  val h = UInt(4.W)
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
  val evenTick = io.tick(0) === 0.U

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

  // OAM Fetch
  val oamIndex = Reg(UInt(7.W))
  io.oam.read := false.B
  io.oam.address := DontCare
  val oamStage = Reg(UInt(3.W))
  val oamAttrs = Reg(new ObjectAttributeFull)
  when (io.enable && active) {
    // TODO don't do all the steps if VRAM fetcher is active
    // Fetch OAM attribute 0 and 1
    when (oamStage === 0.U && evenTick) {
      io.oam.read := true.B
      io.oam.address := Cat(oamIndex, 0.U(1.W))
    }
    // Determine if object is visible
    when (oamStage === 0.U && !evenTick) {
      val attr0 = io.oam.readData(15, 0).asTypeOf(new ObjectAttribute0)
      val attr1 = io.oam.readData(31, 16).asTypeOf(new ObjectAttribute1)
      val (width, height) = getObjectSize(attr0, attr1)
      val y = Wire(UInt(9.W))
      y := attr0.y
      when (attr0.y >= 160.U) {
        y := attr0.y - 256.U
      }
      val objRow = renderY.pad(9) - y

      // TODO store remaining relevant attributes
      oamAttrs.x := attr1.x
      oamAttrs.row := objRow
      oamAttrs.w := width
      oamAttrs.h := height

      when ((objRow >> 3).asUInt < height && !(attr0.double && !attr0.affine)) {
        // This object is in range, and will be rendered.
        printf(cf"[${renderY}] obj visible: i=${oamIndex} row=${objRow}\n")
        oamStage := 1.U
      } .otherwise {
        oamIndex := oamIndex + 1.U
      }
    }
    // Fetch OAM attribute 2
    when (oamStage === 1.U && evenTick) {
      io.oam.read := true.B
      io.oam.address := Cat(oamIndex, 1.U(1.W))
    }
    // Kick off VRAM fetch stage
    when (oamStage === 1.U && !evenTick) {
      val attr2 = io.oam.readData(15, 0).asTypeOf(new ObjectAttribute2)
      // TODO: pass oamAttrs to VRAM fetch stage

      oamStage := 2.U // XXX TEMPORARY: stop here
    }
  }

  // VRAM fetch
  io.vram.read := false.B
  io.vram.address := DontCare

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
      oamIndex := 0.U
      oamStage := 0.U
    }
  }

  def getObjectSize(attr0: ObjectAttribute0, attr1: ObjectAttribute1): (UInt, UInt) = {
    val w = WireDefault(1.U(4.W))
    val h = WireDefault(1.U(4.W))
    switch (attr0.shape) {
      is (0.U) {
        w := 1.U << attr1.size
        h := 1.U << attr1.size
      }
      is (1.U) {
        w := VecInit(2.U, 4.U, 4.U, 8.U)(attr1.size)
        h := VecInit(1.U, 1.U, 2.U, 4.U)(attr1.size)
      }
      is (2.U) {
        w := VecInit(1.U, 1.U, 2.U, 4.U)(attr1.size)
        h := VecInit(2.U, 4.U, 4.U, 8.U)(attr1.size)
      }
    }
    (w, h)
  }
}
