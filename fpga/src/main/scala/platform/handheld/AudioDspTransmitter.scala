package platform.handheld

import chisel3._
import chisel3.util._

/// Transmitter for the "DSP" variant of I2S, where WCLK is a single pulse at the beginning of each frame.
///
/// BCLK frequency will be 1/8 MCLK frequency
class AudioDspTransmitter(
  bitWidth: Int = 16,
  mclkFactor: Int = 256, // MCLK = clockMul * sampleRate
  channels: Int = 2,
) extends Module {
  val io = IO(new Bundle {
    val signals = Output(new I2sSignals)

    /** Whether the audio data is being sampled this cycle. */
    val sampleEnable = Output(Bool())
    /** The audio data for the left channel. */
    val dataLeft = Input(UInt(bitWidth.W))
    /** The audio data for the right channel. */
    val dataRight = Input(UInt(bitWidth.W))
  })
  val bclkDivide = 8
  assert(channels == 2)
  assert(mclkFactor % bclkDivide == 0)
  assert(mclkFactor >= (channels * bitWidth * bclkDivide))

  val sample = RegInit(0.U((bitWidth * channels).W))
  val regWordClock = RegInit(false.B)
  val regBitClock = RegInit(true.B)

  val bitClockCounter = Counter(bclkDivide / 2)
  when (bitClockCounter.inc()) {
    regBitClock := !regBitClock
    when (!regBitClock) {
      // Rising edge of bit clock
      regWordClock := false.B
      sample := sample << 1
    }
  }

  val mclkCounter = Counter(mclkFactor)
  io.sampleEnable := mclkCounter.inc()
  when (io.sampleEnable) {
    sample := Cat(io.dataLeft, io.dataRight)
    regWordClock := true.B
  }

  io.signals.mclk := clock.asBool
  io.signals.wclk := regWordClock
  io.signals.bclk := regBitClock
  io.signals.data := sample(sample.getWidth - 1)
}
