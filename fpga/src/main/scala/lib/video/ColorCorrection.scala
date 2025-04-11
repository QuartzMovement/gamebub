package lib.video

import chisel3._
import lib.log.Logger

/**
 * Color correction matrix calculator
 *
 * Applies a gamma correction to transform to linear space,
 * then a color transformation matrix, then a transform back to non-linear.
 */
class ColorCorrection(
  /// Per-channel input color depth
  inputDepth: Int = 5,
  /// Per-channel output depth
  outputDepth: Int = 6,

  /// Internal representation color depth
  internalDepth: Int = 10,
  /// Internal matrix depth
  matrixDepth: Int = 10,
  /// Internal output table depth
  outputTableDepth: Int = 6,

) extends Module {
  val io = IO(new Bundle {
    /// Enable corrections, or false to pass colors through unchanged.
    val enable = Input(Bool())

    val in = Input(ColorARGB(0, inputDepth, inputDepth, inputDepth))
    val out = Output(ColorARGB(0, outputDepth, outputDepth, outputDepth))

    val matrixR = Input(Vec(3, SInt((matrixDepth + 2).W)))
    val matrixG = Input(Vec(3, SInt((matrixDepth + 2).W)))
    val matrixB = Input(Vec(3, SInt((matrixDepth + 2).W)))
    val inputTable = Input(Vec(1 << inputDepth, SInt((internalDepth + 1).W)))
    val outputTable = Input(Vec(1 << outputTableDepth, UInt(outputDepth.W)))
  })
  val logger = Logger("color")

  // Convert to linear space
  val inputR = RegNext(io.inputTable(io.in.r))
  val inputG = RegNext(io.inputTable(io.in.g))
  val inputB = RegNext(io.inputTable(io.in.b))

  // Do matrix multiplication
  val sumR = (inputR * io.matrixR(0)) + (inputG * io.matrixR(1)) + (inputB * io.matrixR(2))
  val sumG = (inputR * io.matrixG(0)) + (inputG * io.matrixG(1)) + (inputB * io.matrixG(2))
  val sumB = (inputR * io.matrixB(0)) + (inputG * io.matrixB(1)) + (inputB * io.matrixB(2))

  // And divide (fixed point)
  val correctR = RegNext(sumR >> matrixDepth).asSInt
  val correctG = RegNext(sumG >> matrixDepth).asSInt
  val correctB = RegNext(sumB >> matrixDepth).asSInt

  // Clamp at (0.0 and 1.0), and then convert from internal depth to output table depth
  val indexR = clamp(correctR, 0.S, ((1 << internalDepth) - 1).S).asUInt >> (internalDepth - outputTableDepth)
  val indexG = clamp(correctG, 0.S, ((1 << internalDepth) - 1).S).asUInt >> (internalDepth - outputTableDepth)
  val indexB = clamp(correctB, 0.S, ((1 << internalDepth) - 1).S).asUInt >> (internalDepth - outputTableDepth)

  io.out.a := DontCare
  io.out.r := RegNext(io.outputTable(indexR.asUInt))
  io.out.g := RegNext(io.outputTable(indexG.asUInt))
  io.out.b := RegNext(io.outputTable(indexB.asUInt))

  // Original input colors, delayed for the same number of cycles (if corrections are disabled)
  val delayInput = RegNext(RegNext(RegNext(io.in)))
  when (!io.enable) {
    if (outputDepth > inputDepth) {
      io.out.r := delayInput.r << (outputDepth - inputDepth)
      io.out.g := delayInput.g << (outputDepth - inputDepth)
      io.out.b := delayInput.b << (outputDepth - inputDepth)
    } else {
      io.out.r := delayInput.r >> (inputDepth - outputDepth)
      io.out.g := delayInput.g >> (inputDepth - outputDepth)
      io.out.b := delayInput.b >> (inputDepth - outputDepth)
    }
  }

  def clamp(value: SInt, min: SInt, max: SInt): SInt = {
    val output = WireDefault(value)
    when (value < min) {
      output := min
    } .elsewhen (value > max) {
      output := max
    }
    output
  }
}
