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
  val opaque = Bool()
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
  /// Base tile index
  val tile = UInt(10.W)
  val paletteBank = UInt(4.W)
  val bpp8 = Bool()
  val priority = UInt(2.W)
  val flipX = Bool()
  val affine = Bool()
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
  // TODO: this absolutely kills simulation performance relative to a SyncReadMem
  val buffer0 = Reg(Vec(240, UInt((new ObjectBufferEntry).getWidth.W)))
  val buffer1 = Reg(Vec(240, UInt((new ObjectBufferEntry).getWidth.W)))
  val bufferWriteIndex = Wire(UInt(8.W))
  val bufferWriteData = Wire(new ObjectBufferEntry)
  val bufferWriteEnable = WireDefault(false.B)
  val bufferWriteReadback = Wire(new ObjectBufferEntry)
  val bufferPage = Reg(UInt(1.W))
  bufferWriteIndex := DontCare
  bufferWriteData := DontCare
  io.bufferData := Mux(bufferPage === 1.U, buffer0(io.bufferIndex), buffer1(io.bufferIndex)).asTypeOf(new ObjectBufferEntry)
  when (bufferWriteEnable && io.enable) {
    when (bufferPage === 0.U) {
      buffer0(bufferWriteIndex) := bufferWriteData.asUInt
    } .otherwise {
      buffer1(bufferWriteIndex) := bufferWriteData.asUInt
    }
  }
  when (bufferPage === 0.U) {
    bufferWriteReadback := buffer0(bufferWriteIndex).asTypeOf(new ObjectBufferEntry)
  } .otherwise {
    bufferWriteReadback := buffer1(bufferWriteIndex).asTypeOf(new ObjectBufferEntry)
  }

  // Pixel draw
  val drawX = Reg(UInt(8.W))
  val drawCount = RegInit(0.U(2.W))
  val drawData = Reg(Vec(2, new ObjectBufferEntry))
  when (io.enable && drawCount > 0.U) {
    bufferWriteEnable := (drawX < 240.U) &&
      (!bufferWriteReadback.opaque || bufferWriteReadback.priority > bufferWriteData.priority)
    bufferWriteIndex := drawX
    bufferWriteData := drawData(0)
    drawData(0) := drawData(1)
    drawCount := drawCount - 1.U
    drawX := drawX + 1.U
  }

  // VRAM fetch
  io.vram.read := false.B
  io.vram.address := DontCare
  val fetchObj = Reg(new ObjectAttributeFull)
  val fetchCol = Reg(UInt(7.W))
  val fetchActive = RegInit(false.B)
  val allowOam = RegInit(true.B) // Whether the VRAM fetch is blocking OAM fetch
  when (io.enable && fetchActive) {
    when (evenTick) {
      // Fetch from VRAM
      val col = Mux(fetchObj.flipX, fetchCol ^ ((fetchObj.w << 3.U).asUInt - 1.U), fetchCol)
      val tileX = col(6, 3)
      val tileY = fetchObj.row(5, 3)
      val subtileX = col(2, 0)
      val subtileY = fetchObj.row(2, 0)
      // objMapping 1 is 1D, otherwise 2D
      val tileStride = Mux(io.displayControl.objMapping === 1.U, OHToUInt(fetchObj.w), 5.U)
      val tileOffset = tileX + (tileY << tileStride)
      io.vram.read := true.B
      when (fetchObj.bpp8) {
        // 8BPP tiles are 0x40 bytes long, but fetchObj.tile is always in multiples of 0x20 bytes.
        val subtile = Cat(subtileY, subtileX(2, 1))
        io.vram.address := Cat(tileOffset, subtile) + Cat(fetchObj.tile, 0.U(4.W))
      } .otherwise {
        val tile = fetchObj.tile + tileOffset
        val subtile = Cat(subtileY, subtileX(2))
        io.vram.address := Cat(tile, subtile)
      }
    } .otherwise {
      // Move from VRAM to draw queue
      drawX := fetchObj.x + fetchCol - 1.U
      drawCount := 2.U

      when (fetchObj.bpp8) {
        val tileData = io.vram.readData.asTypeOf(Vec(2, UInt(8.W)))
        for (i <- 0 until 2) {
          val color = tileData(i.U ^ fetchObj.flipX)
          drawData(i).opaque := color =/= 0.U
          drawData(i).color := color
          drawData(i).priority := fetchObj.priority
        }
      } .otherwise {
        val tileData = io.vram.readData.asTypeOf(Vec(4, UInt(4.W)))
        for (i <- 0 until 2) {
          val subtileCol = Cat(fetchCol(1), i.U(1.W))
          val color = tileData(Mux(fetchObj.flipX, (~subtileCol).asUInt, subtileCol))
          drawData(i).opaque := color =/= 0.U
          drawData(i).color := Cat(fetchObj.paletteBank, color)
          drawData(i).priority := fetchObj.priority
        }
      }

      // Allow OAM fetch at the last VRAM fetch cycle.
      allowOam := ((fetchCol + 3.U) >> 3.U) === fetchObj.w
    }

    // Increment draw column or end stage.
    val nextCol = fetchCol + 1.U
    when (fetchCol >> 3.U === fetchObj.w) {
      // Done drawing.
      fetchActive := false.B
    } .otherwise {
      fetchCol := nextCol
    }
  }

  // OAM Fetch
  val oamIndex = Reg(UInt(7.W))
  io.oam.read := false.B
  io.oam.address := DontCare
  val oamStage = Reg(UInt(3.W))
  val oamAttrs = Reg(new ObjectAttributeFull)
  val oamAffineIndex = Reg(UInt(5.W))
  when (io.enable && active && allowOam) {
    val advanceIndex = WireDefault(false.B)

    switch (oamStage) {
      is (0.U) {
        // Fetch OAM attribute 0 and 1
        when (evenTick) {
          io.oam.read := true.B
          io.oam.address := Cat(oamIndex, 0.U(1.W))
        } .otherwise {
          val attr0 = io.oam.readData(15, 0).asTypeOf(new ObjectAttribute0)
          val attr1 = io.oam.readData(31, 16).asTypeOf(new ObjectAttribute1)
          val (width, height) = getObjectSize(attr0, attr1)
          val y = Wire(UInt(9.W))
          y := attr0.y
          when (attr0.y >= 160.U) {
            y := attr0.y - 256.U
          }
          val objRow = renderY.pad(9) - y

          oamAttrs.x := attr1.x
          oamAttrs.row := Mux(attr1.flipY, objRow ^ ((height << 3.U).asUInt - 1.U), objRow)
          oamAttrs.w := width
          oamAttrs.h := height
          oamAttrs.bpp8 := attr0.bpp8
          oamAttrs.flipX := attr1.flipX
          oamAttrs.affine := attr0.affine
          oamAffineIndex := Cat(attr1.flipY, attr1.flipX, attr1.affineIndexLo)

          val affineDouble = attr0.affine && attr0.double
          val inRange = (objRow >> Mux(affineDouble, 4.U, 3.U)).asUInt < height
          when (inRange && !(attr0.double && !attr0.affine)) {
            // This object is in range, and will be rendered.
            //        printf(cf"[${renderY}] obj visible: i=${oamIndex} row=${objRow}\n")
            oamStage := 1.U
          } .otherwise {
            advanceIndex := true.B
          }
        }
      }
      is (1.U) {
        // Fetch OAM attribute 2
        when (evenTick) {
          io.oam.read := true.B
          io.oam.address := Cat(oamIndex, 1.U(1.W))
        } .otherwise {
          val attr2 = io.oam.readData(15, 0).asTypeOf(new ObjectAttribute2)

          // Set up draw stage state
          fetchObj := oamAttrs
          fetchObj.tile := attr2.tile
          fetchObj.paletteBank := attr2.paletteBank
          fetchObj.priority := attr2.priority

          when (fetchObj.affine) {
            // Fetch matrix coefficients
            oamStage := 2.U
          } .otherwise {
            // Go to draw stage
            fetchCol := 0.U
            fetchActive := true.B
            advanceIndex := true.B
          }
        }
      }
    }

    when (advanceIndex) {
      val nextOamIndex = oamIndex + 1.U
      when (nextOamIndex === 0.U) {
        // End of scan
        oamStage := 7.U
      } .otherwise {
        oamStage := 0.U
        oamIndex := nextOamIndex
      }
    }
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
      when (bufferPage === 0.U) {
        buffer1 := VecInit(Seq.fill(240)(0.U))
      } .otherwise {
        buffer0 := VecInit(Seq.fill(240)(0.U))
      }
      oamIndex := 0.U
      oamStage := 0.U
      allowOam := true.B
      fetchActive := false.B
      drawCount := 0.U
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
