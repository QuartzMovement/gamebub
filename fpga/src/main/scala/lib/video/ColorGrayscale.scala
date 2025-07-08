package lib.video

import chisel3._

object ColorGrayscale {
  def apply(a: Int, lum: Int): ColorGrayscale = {
    new ColorGrayscale(a, lum)
  }
}

class ColorGrayscale(aWidth: Int, lumWidth: Int) extends Color {
  val a = UInt(aWidth.W)
  val lum = UInt(lumWidth.W)

  override def convertTo[T](gen: T): T = gen match {
    case c: ColorARGB => {
      val out = Wire(c.cloneType)
      out.a := Color.convertA(a, c.a)
      out.r := Color.convertRGB(lum, c.r)
      out.g := Color.convertRGB(lum, c.g)
      out.b := Color.convertRGB(lum, c.b)
      out.asInstanceOf[T]
    }
    case c: ColorGrayscale => {
      val out = Wire(c.cloneType)
      out.a := Color.convertA(a, c.a)
      out.lum := Color.convertRGB(lum, c.lum)
      out.asInstanceOf[T]
    }
  }
}
