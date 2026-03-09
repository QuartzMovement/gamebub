package net.gamebub.framework.interface

import chisel3._
import chisel3.util._
import lib.video.ColorARGB

class VideoV0(
  /// Width of the video output, in pixels
  val videoWidth: Int,
  /// Height of the video output, in pixels
  val videoHeight: Int,
  /// Bits per each R, G, B color channel
  val colorDepth: Int,
  /// Target frame period, in seconds.
  val framePeriod: Double,
) extends Bundle {
  val x = Output(UInt(log2Ceil(videoWidth).W))
  val y = Output(UInt(log2Ceil(videoHeight).W))
  val data = Output(ColorARGB.apply(0, colorDepth, colorDepth, colorDepth))
  val dataEnable = Output(Bool())
  val vblank = Output(Bool())
}
