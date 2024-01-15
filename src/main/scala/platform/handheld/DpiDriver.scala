package platform.handheld

import chisel3._
import chisel3.util._

class DpiSignals extends Bundle {
  /** Active high */
  val vsync = Bool()
  /** Active high */
  val hsync = Bool()
  /** Active high */
  val enable = Bool()
  val dotclk = Clock()
}

/**
 * Display Parallel Interface (DPI) driver.
 * Manages the control signals, caller sends the pixel data.
 */
class DpiDriver(
  /** Width of the active area (pixels) */
  hActive: Int,
  /** Length of the horizontal sync period (clocks) */
  hSync: Int,
  /** Length of the horizontal back porch (clocks) */
  hBackPorch: Int,
  /** Length of the horizontal front porch (clocks) */
  hFrontPorch: Int,
  /** Height of the active area (pixels) */
  vActive: Int,
  /** Length of the vertical sync period (lines) */
  vSync: Int,
  /** Length of the vertical back porch (lines) */
  vBackPorch: Int,
  /** Length of the vertical front porch (lines) */
  vFrontPorch: Int,
) extends Module {
  val io = IO(new Bundle {
    val signals = Output(new DpiSignals)

    val pixelX = Output(UInt(log2Ceil(hActive).W))
    val pixelY = Output(UInt(log2Ceil(vActive).W))
  })

  val totalWidth = hActive + hSync + hBackPorch + hFrontPorch
  val totalHeight = vActive + vSync + vBackPorch + vFrontPorch

  val x = RegInit(0.U(log2Ceil(totalWidth).W))
  val y = RegInit(0.U(log2Ceil(totalHeight).W))
  when (x === (totalWidth - 1).U) {
    x := 0.U
    when (y === (totalHeight - 1).U) {
      y := 0.U
    } .otherwise {
      y := y + 1.U
    }
  } .otherwise {
    x := x + 1.U
  }

  io.pixelX := x - (hSync + hBackPorch).U
  io.pixelY := y - (vSync + vBackPorch).U

  io.signals.dotclk := clock
  io.signals.enable :=
      (x >= (hSync + hBackPorch).U) &&
        (x < (hSync + hBackPorch + hActive).U) &&
        (y >= (vSync + vBackPorch).U) &&
        (y < (vSync + vBackPorch + vActive).U)
  io.signals.hsync := x < hSync.U
  io.signals.vsync := y < vSync.U
}
