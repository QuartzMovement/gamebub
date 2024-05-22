package gba.ppu

import chisel3._
import chisel3.util._

class BackgroundPixel extends Bundle {
  // Whether the pixel is valid and opaque.
  val valid = Bool()
  // Palette index (or, layer 2 and 3 in mode 3 and 5 combine to form a 16-bit color).
  val color = UInt(8.W)
}

class BackgroundLayerState extends Bundle {
  /// Whether the layer is active during this part of the scanline.
  val active = Bool()
  val pos = UInt(8.W)
}

class BackgroundRenderer extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    val displayControl = Input(new PpuRegisters.DisplayControl)

    /// BG VRAM access
    val vram = Flipped(new PpuMemoryInterface(96 * 1024 / 2, 16.W))

    /// Current cycle in the scanline
    val tick = Input(UInt(11.W))
    val scanline = Input(UInt(8.W))

    /// Pixel fifo dequeue interface
    val pixels = Vec(4, DecoupledIO(new BackgroundPixel))
  })

  // Output pixel FIFOs
  val fifo = (0 until 4).map(_ => Wire(EnqIO(new BackgroundPixel)))
  for (i <- 0 until 4) {
    fifo(i).valid := false.B
    fifo(i).bits := DontCare
  }
  val fifoFlush = WireDefault(false.B)
  io.pixels <> VecInit((0 until 4).map(i => Queue(fifo(i), entries = 5, flush = Some(fifoFlush))))

  // Per-layer state
  val layer = Reg(Vec(4, new BackgroundLayerState))

  // Index within a subpixel (0..3) that is being fetched, then is being used.
  val subFetch = io.tick(1, 0) + 1.U
  val subUse = io.tick(1, 0) + 2.U
  val isVdraw = io.scanline < 160.U

  // TODO: this is hardcoded to 8bpp bitmap
  io.vram.read := false.B
  io.vram.address := DontCare

  switch (io.displayControl.mode) {
    is (0.U) {
      when (io.displayControl.enableBg(0)) { renderRegularLayer(0) }
      when (io.displayControl.enableBg(1)) { renderRegularLayer(1) }
      when (io.displayControl.enableBg(2)) { renderRegularLayer(2) }
      when (io.displayControl.enableBg(3)) { renderRegularLayer(3) }
    }
    is (1.U) {
      when (io.displayControl.enableBg(0)) { renderRegularLayer(0) }
      when (io.displayControl.enableBg(1)) { renderRegularLayer(1) }
      when (io.displayControl.enableBg(2)) { renderAffineLayer(2) }
    }
    is (2.U) {
      when (io.displayControl.enableBg(2)) { renderAffineLayer(2) }
      when (io.displayControl.enableBg(3)) { renderAffineLayer(3) }
    }
    is (3.U, 4.U, 5.U) {
      when (io.displayControl.enableBg(2)) { renderBitmapLayer() }
    }
  }

  when (io.enable && isVdraw) {
    when (io.tick === 1005.U) {
      // Begin HBlank
//      printf(cf"[BG] hblank for ${io.scanline}\n")
      for (i <- 0 until 4) {
        layer(i).active := false.B
        layer(i).pos := 0.U
      }
      fifoFlush := true.B
    }
  }

  private def renderRegularLayer(index: Int): Unit = {
    // TODO
  }

  private def renderAffineLayer(index: Int): Unit = {
    // TODO
  }

  private def renderBitmapLayer(): Unit = {
    // Activate
    when (io.enable && isVdraw && io.tick === 30.U) {
      layer(2).active := true.B
    }

    // Render
    when (layer(2).active) {
      when (subFetch === 3.U) {
        io.vram.read := true.B
        val frameOffset = Mux(io.displayControl.frame === 1.U, (0xA000 / 2).U, 0.U)
        switch (io.displayControl.mode) {
          is (3.U) {
            // 240x160, 16bpp
            io.vram.address := ((io.scanline * 240.U) + layer(2).pos)
          }
          is (4.U) {
            // 240x160, indexed 8bpp
            io.vram.address := (((io.scanline * 240.U) + layer(2).pos) >> 1).asUInt.pad(16) + frameOffset
          }
          is (5.U) {
            // 160x128, 16bpp
            io.vram.address := ((io.scanline * 160.U) + layer(2).pos) + frameOffset
          }
        }
        //      printf(cf"[BG] fetch ${io.vram.address}%x | tick=${io.tick} | scan=${io.scanline} p=${layerPos(2)}  \n")
      }
      when (subUse === 3.U) {
        layer(2).pos := layer(2).pos + 1.U
        fifo(2).valid := true.B
        fifo(2).bits.valid := true.B

        switch (io.displayControl.mode) {
          is (4.U) {
            fifo(2).bits.color := Mux(
              layer(2).pos(0) === 0.U,
              io.vram.readData(7, 0), io.vram.readData(15, 8)
            )
          }
          is (3.U, 5.U) {
            fifo(3).valid := true.B

            fifo(2).bits.color := io.vram.readData(7, 0)
            fifo(3).bits.color := io.vram.readData(15, 8)
            when (io.displayControl.mode === 5.U) {
              when (io.scanline >= 128.U || layer(2).pos >= 160.U) {
                fifo(2).bits.valid := false.B
              }
            }
          }
        }
        //      printf(cf"[BG] inserting pix ${io.vram.readData}\n")
      }
    }
  }
}
