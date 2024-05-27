package gba.ppu

import chisel3._
import chisel3.util._
import gba.{MmioMap, MmioTarget}
import gba.mem.TargetInterface

class PpuOutput extends Bundle {
  /** Output pixel value (B G R) */
  val pixel = UInt(15.W)
  /** Whether the pixel this clock is valid */
  val valid = Bool()
  /** Whether the PPU is in hblank */
  val hblank = Bool()
  /** Whether the PPU is in vblank */
  val vblank = Bool()
}

class Ppu extends Module {
  val io = IO(new Bundle {
    /// Global enable
    val enable = Input(Bool())

    /// PPU output
    val output = Output(new PpuOutput)

    /// VRAM memory target for CPU
    val vramTarget = new TargetInterface(16.W)

    /// Palette memory target for CPU
    val paletteRamTarget = new TargetInterface(16.W)

    /// MMIO access
    val mmio = new MmioTarget()

    /// Interrupts
    val irqVblank = Output(Bool())
    val irqHblank = Output(Bool())
    val irqVcount = Output(Bool())
  })

  val regDisplayControl = RegInit(0.U.asTypeOf(new PpuRegisters.DisplayControl))
  val regIrqEnableVblank = RegInit(false.B)
  val regIrqEnableHblank = RegInit(false.B)
  val regIrqEnableVcount = RegInit(false.B)
  val regVcount = RegInit(0.U(8.W))
  val regBgControl = RegInit(VecInit(Seq.fill(4)(0.U.asTypeOf(new PpuRegisters.BackgroundControl))))
  val regBgOffX = RegInit(VecInit(Seq.fill(4)(0.U(16.W))))
  val regBgOffY = RegInit(VecInit(Seq.fill(4)(0.U(16.W))))
  // TODO: should this be initialized with pa and pd at 0x100?
  val regBgAff = RegInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new PpuRegisters.BackgroundAffineParams))))
  val regBgAffX = RegInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new PpuRegisters.AffineReferencePoint))))
  val regBgAffY = RegInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new PpuRegisters.AffineReferencePoint))))

  /// VRAM: 96KiB, 16-bit access without byte strobe. Note: actually split into multiple banks for bg/obj
  val vram = Module(new Vram)
  vram.io.enable := io.enable
  vram.io.memTarget <> io.vramTarget

  val paletteRam = Module(new PpuMem(1024 / 2, 16.W))
  paletteRam.io.enable := io.enable
  paletteRam.io.memTarget <> io.paletteRamTarget

  val scanline = RegInit(0.U(8.W))
  val tick = RegInit(0.U(11.W))
  val vcountHit = scanline === regVcount

  when (io.enable) {
    when (tick < 1232.U) {
      tick := tick + 1.U
    } .otherwise {
      tick := 0.U
      when (scanline < 228.U) {
        scanline := scanline + 1.U
      } .otherwise {
        scanline := 0.U
      }
    }
  }

  io.output.hblank := tick >= 1006.U
  io.output.vblank := scanline >= 160.U

  // I/O registers
  io.mmio <> MmioMap(
    0x0 -> MmioMap.Entry.rw(regDisplayControl),
    0x4 -> MmioMap.Entry(
      // DISPSTAT and VCOUNT
      MmioMap.ReadFn(_ => {
        val status = Wire(new PpuRegisters.DisplayStatus)
        status.vblank := io.output.vblank
        status.hblank := io.output.hblank
        status.vcountHit := vcountHit
        status.irqVblank := regIrqEnableVblank
        status.irqHblank := regIrqEnableHblank
        status.irqVcount := regIrqEnableVcount
        val data = Cat(
          0.U(8.W),
          scanline,
          regVcount,
          status.asUInt.pad(8),
        )
        (data, true.B)
      }),
      MmioMap.WriteFn((enable, data, mask) => {
        when (enable) {
          when (mask(0)) {
            val newData = data(7, 0).asTypeOf(new PpuRegisters.DisplayStatus)
            regIrqEnableVblank := newData.irqVblank
            regIrqEnableHblank := newData.irqHblank
            regIrqEnableVcount := newData.irqVcount
          }
          when (mask(1)) {
            regVcount := data(15, 8)
          }
        }
      })
    ),
    0x8 -> MmioMap.Entry.rw16(regBgControl(0), regBgControl(1)),
    0xC -> MmioMap.Entry.rw16(regBgControl(2), regBgControl(3)),
    0x10 -> MmioMap.Entry.w16(regBgOffX(0), regBgOffY(0)),
    0x14 -> MmioMap.Entry.w16(regBgOffX(1), regBgOffY(1)),
    0x18 -> MmioMap.Entry.w16(regBgOffX(2), regBgOffY(2)),
    0x1C -> MmioMap.Entry.w16(regBgOffX(3), regBgOffY(3)),
    0x20 -> MmioMap.Entry.w16(regBgAff(0).pa, regBgAff(0).pb),
    0x24 -> MmioMap.Entry.w16(regBgAff(0).pc, regBgAff(0).pd),
    // TODO: writing these is supposed to update the latched value immediately?
    0x28 -> MmioMap.Entry.w(regBgAffX(0)),
    0x2C -> MmioMap.Entry.w(regBgAffY(0)),
    0x30 -> MmioMap.Entry.w16(regBgAff(1).pa, regBgAff(1).pb),
    0x34 -> MmioMap.Entry.w16(regBgAff(1).pc, regBgAff(1).pd),
    // TODO: writing these is supposed to update the latched value immediately?
    0x38 -> MmioMap.Entry.w(regBgAffX(1)),
    0x3C -> MmioMap.Entry.w(regBgAffY(1)),
  )

  // Background renderer
  val bgRender = Module(new BackgroundRenderer)
  bgRender.io.enable := io.enable
  bgRender.io.displayControl := regDisplayControl
  bgRender.io.bgControl := regBgControl
  bgRender.io.bgOffX := regBgOffX
  bgRender.io.bgOffY := regBgOffY
  bgRender.io.bgAff := regBgAff
  bgRender.io.bgAffX := regBgAffX
  bgRender.io.bgAffY := regBgAffY
  bgRender.io.tick := tick
  bgRender.io.scanline := scanline
  bgRender.io.vram <> vram.io.portBG

  // Compositor
  val compositor = Module(new Compositor)
  compositor.io.enable := io.enable
  compositor.io.displayControl := regDisplayControl
  compositor.io.tick := tick
  compositor.io.scanline := scanline
  compositor.io.paletteRam <> paletteRam.io.ppuTarget
  compositor.io.bgFifo <> bgRender.io.pixels
  io.output.valid := compositor.io.valid
  io.output.pixel := compositor.io.pixel

  // IRQs
  {
    val lastVblank = RegInit(false.B)
    val lastHblank = RegInit(false.B)
    val lastVcountHit = RegInit(false.B)
    when (io.enable) {
      lastHblank := io.output.hblank
      lastVblank := io.output.vblank
      lastVcountHit := vcountHit
    }
    io.irqHblank := regIrqEnableHblank && io.output.hblank && !lastHblank
    io.irqVblank := regIrqEnableVblank && io.output.vblank && !lastVblank
    io.irqVcount := regIrqEnableVcount && vcountHit && !lastVcountHit
  }
}
