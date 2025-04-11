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

) extends Module {
  val io = IO(new Bundle {
    /// Enable corrections, or false to pass colors through unchanged.
    val enable = Input(Bool())

    val in = Input(ColorARGB(0, inputDepth, inputDepth, inputDepth))
    val out = Output(ColorARGB(0, outputDepth, outputDepth, outputDepth))
  })
  val logger = Logger("color")

  // Correction formula and coefficients credit: hunterk and Pokefan531
  val targetGamma = 2.2
  val displayGamma = 2.2
  val luminance = 0.91
  val rowR = Seq( 0.905 ,  0.195 , -0.1 )
  val rowG = Seq( 0.1   ,  0.65  ,  0.25)
  val rowB = Seq( 0.1575,  0.1425,  0.7 )

  // Input table (to linear)
  val inputTable = VecInit(
    (0 until (1 << inputDepth)).map(i => {
        // Normalized 0.0 to 1.0
        val normal = i.toDouble / ((1 << inputDepth) - 1).toDouble
        val linear = scala.math.pow(normal, targetGamma)
        val screen = linear * luminance
        // Convert back to float: clamp(floor(f * X), 0, X - 1)
        val integer = (screen * (1 << internalDepth).toDouble).floor.min((1 << internalDepth) - 1).max(0).toInt
        integer.S((internalDepth + 1).W)
      }
    )
  )

  // Transformation matrix
  val matrixR = VecInit(rowR.map(x => (x * (1 << matrixDepth)).toInt.S((matrixDepth + 2).W)))
  val matrixG = VecInit(rowG.map(x => (x * (1 << matrixDepth)).toInt.S((matrixDepth + 2).W)))
  val matrixB = VecInit(rowB.map(x => (x * (1 << matrixDepth)).toInt.S((matrixDepth + 2).W)))

  // Output table (to non-linear)
  val outputTableDepth = 6
  val outputTable = VecInit(
    (0 until (1 << outputTableDepth)).map(i => {
      val normal = i.toDouble / ((1 << outputTableDepth) - 1).toDouble
      val nonlinear = scala.math.pow(normal, 1.0 / displayGamma)
      val integer = (nonlinear * (1 << outputDepth).toDouble).floor.min((1 << outputDepth) - 1).max(0).toInt
      integer.U(outputDepth.W)
    })
  )

  // Convert to linear space
  val inputR = RegNext(inputTable(io.in.r))
  val inputG = RegNext(inputTable(io.in.g))
  val inputB = RegNext(inputTable(io.in.b))

  // Do matrix multiplication
  val sumR = (inputR * matrixR(0)) + (inputG * matrixR(1)) + (inputB * matrixR(2))
  val sumG = (inputR * matrixG(0)) + (inputG * matrixG(1)) + (inputB * matrixG(2))
  val sumB = (inputR * matrixB(0)) + (inputG * matrixB(1)) + (inputB * matrixB(2))

  // And divide (fixed point)
  val correctR = RegNext(sumR >> matrixDepth).asSInt
  val correctG = RegNext(sumG >> matrixDepth).asSInt
  val correctB = RegNext(sumB >> matrixDepth).asSInt

  // Clamp at (0.0 and 1.0), and then convert from internal depth to output table depth
  val indexR = clamp(correctR, 0.S, ((1 << internalDepth) - 1).S).asUInt >> (internalDepth - outputTableDepth)
  val indexG = clamp(correctG, 0.S, ((1 << internalDepth) - 1).S).asUInt >> (internalDepth - outputTableDepth)
  val indexB = clamp(correctB, 0.S, ((1 << internalDepth) - 1).S).asUInt >> (internalDepth - outputTableDepth)

  io.out.a := DontCare
  io.out.r := RegNext(outputTable(indexR.asUInt))
  io.out.g := RegNext(outputTable(indexG.asUInt))
  io.out.b := RegNext(outputTable(indexB.asUInt))

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
