package platform.handheld.boot

import chisel3._
import chisel3.util._
import lib.mem.{MemoryInterface, RegisterMap}
import lib.video.ColorARGB

import java.awt.Color
import javax.imageio.ImageIO

class LogoAnimationState extends Bundle {
  val time = UInt(8.W)
  val speed = UInt(4.W) // duration = (4 seconds / "speed")
  val loop = Bool()
  val running = Bool()
}

class Logo(framebufferW: Int, framebufferH: Int) extends Module {
  val io = IO(new Bundle {
    val registers = new MemoryInterface(addressWidth = 16, dataWidth = 32)

    val framebufferX = Output(UInt(8.W))
    val framebufferY = Output(UInt(8.W))
    val framebufferData = Output(ColorARGB.rgb555())
    val framebufferWriteEnable = Output(Bool())
    val vblank = Output(Bool())
  })

  val regX = RegInit(0.U(log2Ceil(framebufferW).W))
  val regY = RegInit(0.U(log2Ceil(framebufferH).W))

  val regAnimation = RegInit(0.U.asTypeOf(new LogoAnimationState))
  val regLogoStartY = RegInit(26.U(8.W))
  val (_, frame) = Counter(true.B, 16 * 1024 * 1024 / 64)

  io.registers <> RegisterMap(
    addressWidth = 8,
    dataWidth = 32,
    entries = Seq(
      0x0 -> RegisterMap.Entry.rw(regAnimation),
      0x4 -> RegisterMap.Entry.rw(regLogoStartY),
    )
  )

  // Load and process logo
  val (logoW, logoH, logoData) = loadLogo()
  val logo = VecInit(logoData.map(x => x.U(8.W)))
  val bgColor = Wire(ColorARGB.rgb555())
  bgColor.a := 0.U
  bgColor.r := (0xE8 >> 3).U(5.W)
  bgColor.g := (0xE8 >> 3).U(5.W)
  bgColor.b := (0xE8 >> 3).U(5.W)
  val logoStartX = (framebufferW - logoW) / 2
  val logoEndX = (framebufferW + logoW) / 2
  val colorTable = makeColorTable(logoW)
  val colorOffX = RegInit(0.U(log2Ceil(3 * logoW).W))

  when (frame) {
    regX := 0.U
    regY := 0.U

    when (regAnimation.running) {
      val nextTime = regAnimation.time + regAnimation.speed
      when ((nextTime < regAnimation.time) && !regAnimation.loop) {
        regAnimation.time := 0.U
        regAnimation.running := false.B
      } .otherwise {
        regAnimation.time := nextTime
      }
    }

    // TODO calculate colorOffX with the curve
    colorOffX := ((regAnimation.time * (logoW * 2).U) >> 8).asUInt
  } .otherwise {
    when (regY === (framebufferH - 1).U) {
      when (regX < framebufferW.U) {
        regY := 0.U
        regX := regX + 1.U
      }
    } .otherwise {
      regY := regY + 1.U
    }
  }
  io.framebufferX := regX
  io.framebufferY := regY
  io.framebufferWriteEnable := regX < framebufferW.U
  io.vblank := !io.framebufferWriteEnable
  io.framebufferData.a := DontCare

  io.framebufferData.r := bgColor.r
  io.framebufferData.g := bgColor.g
  io.framebufferData.b := bgColor.b
  when (regX >= logoStartX.U && regX < logoEndX.U && regY >= regLogoStartY && regY < (regLogoStartY + logoH.U)) {
    // TODO read one or two early
    val x = regX - logoStartX.U
    val y = regY - regLogoStartY
    val alpha = logo((x * logoH.U + y)(log2Ceil(logoData.length) - 1, 0))

    val colorX = colorOffX + x
    val color = WireDefault(colorTable(0))
    when (colorX >= logoW.U && colorX < (logoW * 2).U) {
      color := colorTable(colorX - logoW.U)
    }

    // Alpha blend: out = (A * alpha) + (1 - alpha) * B
    // Division by 256 will be slightly off, because the alpha is between 0 and 255.
    io.framebufferData.r := blend(color.r, bgColor.r, alpha)
    io.framebufferData.g := blend(color.g, bgColor.g, alpha)
    io.framebufferData.b := blend(color.b, bgColor.b, alpha)
  }

  private def blend(a: UInt, b: UInt, alpha: UInt): UInt = {
    (((a * alpha) + ((0x20.U(6.W) - alpha) * b)) >> 5).asUInt
  }

  private def loadLogo(): (Int, Int, Seq[Int]) = {
    val logo = ImageIO.read(getClass.getClassLoader.getResource("logo.png"))
    val data = (0 until (logo.getWidth * logo.getHeight)).map(i => {
      val x = i / logo.getHeight
      val y = i % logo.getHeight
      val alpha = (logo.getRGB(x, y) >> 24) & 0xFF
      alpha >> 3 // convert to 5 bit color
    })
    (logo.getWidth, logo.getHeight, data)
  }

  /// Make the gradient color table
  private def makeColorTable(width: Int) = {
    VecInit((0 until width).map(x => {
      val t = x.toDouble / (width - 1)
      val delta = (-4.0 * t * (t - 1.0))

      val hsbVals = Color.RGBtoHSB(0x81, 0x0F, 0x97, null)
      hsbVals(2) += (delta * 0.5).toFloat
      val out = Color.getHSBColor(hsbVals(0), hsbVals(1), hsbVals(2).min(1))
      val color = Wire(ColorARGB.rgb555())
      color.a := DontCare
      color.r := (out.getRed >> 3).U(5.W)
      color.g := (out.getGreen >> 3).U(5.W)
      color.b := (out.getBlue >> 3).U(5.W)
      color
    }))
  }
}
