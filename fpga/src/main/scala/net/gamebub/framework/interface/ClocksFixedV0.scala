package net.gamebub.framework.interface

import chisel3._

class ClocksFixedV0(
  /// Divider of the (50 / 3 * 56.375) MHz MMCM clock
  val sysDivider: Int,
  /// Divider of the (50 / 3 * 56.375) MHz MMCM clock
  val sdramDivider: Int,
) extends Bundle {
  val clockSdram = Input(Clock())

  def clockSystemHz: Int = (50_000_000.toDouble / 3 * 56.375 / sysDivider).toInt
  def clockSdramHz: Int = (50_000_000.toDouble / 3 * 56.375 / sdramDivider).toInt
}
