package platform.handheld

import chisel3._
import chisel3.util._

/**
 * Display Parallel Interface (DPI) driver.
 * Manages the control signals, caller sends the pixel data.
 *
 * Adaptive: will vary the framerate (slightly) to match the input frame rate.
 */
class AdaptiveDpiDriver(
  clockHz: Int,
  /** Typical source frame period (seconds)  */
  sourceFramePeriod: Double,

  /** Width of the active area (pixels) */
  hActive: Int,
  /** Minimum horizontal sync period (clocks) */
  hSyncMin: Int = 3,
  /** Minimum horizontal back porch (clocks) */
  hBackPorchMin: Int = 3,
  /** Minimum horizontal front porch (clocks) */
  hFrontPorchMin: Int = 3,
  /** Height of the active area (pixels) */
  vActive: Int,
  /** Length of the vertical sync period (lines) */
  vSyncMin: Int = 1,
  /** Length of the vertical back porch (lines) */
  vBackPorchMin: Int = 2,
  /** Length of the vertical front porch (lines) */
  vFrontPorchMin: Int = 2,
) extends Module {
  val io = IO(new Bundle {
    val signals = Output(new DpiSignals)

    val pixelX = Output(UInt(log2Ceil(hActive).W))
    val pixelY = Output(UInt(log2Ceil(vActive).W))

    /// Last rendered frame index
    val lastRenderedFrame = Input(UInt(1.W))
    /// Current display frame index
    val displayFrame = Output(UInt(1.W))
  })

  assert(hActive > 0)
  assert(hSyncMin > 0)
  assert(hBackPorchMin > 0)
  assert(hFrontPorchMin > 0)
  assert(vActive > 0)
  assert(vSyncMin > 0)
  assert(vBackPorchMin > 0)
  assert(vFrontPorchMin > 0)

  /*
  hsync + hbp < 192
  vsync + vbp + vfp < 32

  Strategy:
  * Make vsync and vbp as low as possible -- extend vfp if needed
  * Set things up such that VFP can extend the frame by about 1%

  "Recommendation: The porch number of VBP + VFP must be even."
  */

  // Calculate timing
  val vSync = vSyncMin
  val vBackPorch = vBackPorchMin
  val vFrontPorchMax = 32 - vSync - vBackPorch - 1
  val hSync = hSyncMin
  val hBackPorch = hBackPorchMin

  // With maximum vFrontPorch, target ~1.02x the sourceFramePeriod
  val totalHeightMin = vActive + vSync + vBackPorch + vFrontPorchMin
  val totalHeightMax = vActive + vSync + vBackPorch + vFrontPorchMax
  val maxFrameCycles = 1.02 * clockHz * sourceFramePeriod
  val approxFrameWidth = (maxFrameCycles / totalHeightMax).round.toInt
  val hFrontPorch = approxFrameWidth - (hActive + hSync + hBackPorch)
  val totalWidth = hActive + hSync + hBackPorch + hFrontPorch

  val regHsync = RegInit(true.B)
  val regVsync = RegInit(true.B)
  val regActive = RegInit(false.B)
  io.signals.dotclk := clock
  io.signals.hsync := regHsync
  io.signals.vsync := regVsync
  io.signals.enable := regActive

  val x = RegInit(0.U(log2Ceil(totalWidth).W))
  val y = RegInit(0.U(log2Ceil(totalHeightMax).W))
  io.pixelX := x - (hSync + hBackPorch).U
  io.pixelY := y - (vSync + vBackPorch).U

  val currentFrame = RegInit(0.U(1.W))
  /// Whether the display is currently synchronized with the render
  val regLocked = RegInit(true.B)

  val newFrameReady = io.lastRenderedFrame =/= currentFrame
  io.displayFrame := currentFrame

  when (x === (totalWidth - 1).U) {
    // Scanline is done
    regHsync := true.B
    x := 0.U
    y := y + 1.U

    val startFrame = WireDefault(false.B)
    when (startFrame) {
      regVsync := true.B
      y := 0.U
      currentFrame := io.lastRenderedFrame
    }

    when (!regLocked && newFrameReady) {
      // Immediately display new frame (interrupting current frame)
      regLocked := true.B
      startFrame := true.B
    } .elsewhen (y === (vSync - 1).U) {
      regVsync := false.B
    } .elsewhen ((y >= (totalHeightMin - 1).U) && newFrameReady) {
      // New frame available, start rendering.
      startFrame := true.B
    } .elsewhen (y === (totalHeightMax - 1).U) {
      // Hit the maximum allowed total height without a new frame coming in:
      // source is too slow, switch to rapid refresh (no longer locked)
      regLocked := false.B
      startFrame := true.B
    }
  } .otherwise {
    x := x + 1.U
    when (x === (hSync - 1).U) {
      regHsync := false.B
    }
    val isVActive = (y >= (vSync + vBackPorch).U) && (y < (vSync + vBackPorch + vActive).U)
    when (x === (hSync + hBackPorch - 1).U && isVActive) {
      regActive := true.B
    }
    when (x === (hSync + hBackPorch + hActive - 1).U) {
      regActive := false.B
    }
  }
}
