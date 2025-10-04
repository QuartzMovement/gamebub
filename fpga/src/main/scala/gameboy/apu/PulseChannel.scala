package gameboy.apu

import chisel3._
import chisel3.util._

class PulseChannelIO extends ChannelIO {
  val lengthConfig = Input(new LengthControlConfig(6))
  val volumeConfig = Input(new VolumeEnvelopeConfig)

  val wavelength = Input(UInt(11.W))
  val duty = Input(UInt(2.W))
}

/**
 * Base for channel 1 and channel 2.
 *
 * Internal counter (0..7) that selects either high or low from the duty cycle table.
 * Each step of this counter takes ((2048 - wavelength) * 4) cycles (full 4Mhz cycles).
 */
class PulseChannel extends Module {
  val io = IO(new PulseChannelIO)

  // Length control module.
  val lengthUnit = Module(new LengthControl(6))
  lengthUnit.io.trigger := io.trigger
  lengthUnit.io.config := io.lengthConfig
  lengthUnit.io.tick := io.ticks.length

  // Volume control module.
  val volumeUnit = Module(new VolumeEnvelope)
  volumeUnit.io.trigger := io.trigger
  volumeUnit.io.config := io.volumeConfig
  volumeUnit.io.tick := io.ticks.volume

  // Counter within the wave table. Only reset when APU turns off.
  val waveIndex = RegInit(0.U(3.W))
  // Counter that advances the waveIndex. Reset on trigger.
  val waveCounter = RegInit(0.U(14.W))
  // Wave counter counts up to this value.
  val waveCounterMax = (2048.U(14.W) - io.wavelength) << 2

  when (io.trigger) {
    waveCounter := waveCounterMax
  }

  when (io.pulse4Mhz) {
    when(waveCounter === 0.U) {
      waveIndex := waveIndex + 1.U
      waveCounter := waveCounterMax
    }.otherwise {
      waveCounter := waveCounter - 1.U
    }
  }

  io.dacEnabled := io.volumeConfig.initialVolume =/= 0.U || io.volumeConfig.modeIncrease
  io.channelDisable := lengthUnit.io.channelDisable
  io.out := Mux(
    VecInit(waveIndex < 1.U, waveIndex < 2.U, waveIndex < 4.U, waveIndex < 6.U)(io.duty),
    volumeUnit.io.out, 0.U
  )
}

class FrequencySweepConfig extends Bundle {
  val pace = UInt(3.W)
  val decrease = Bool()
  val slope = UInt(3.W)
}

/** Channel 1: Pulse, but with additional frequency sweep **/
class PulseChannelWithSweep extends Module {
  val io = IO(new PulseChannelIO {
    val sweepConfig = Input(new FrequencySweepConfig)

    // Since there's an internal copy of wavelength, we need
    // to be told when to load the wavelength. This is a byte write mask.
    val wavelengthLoad = Input(UInt(2.W))
  })

  val regWavelength = RegInit(0.U(11.W))

  // Frequency sweep
  val freqSweepShadow = RegInit(0.U(11.W))
  val freqSweepEnabled = RegInit(false.B)
  val freqSweepTimer = RegInit(0.U(3.W))
  val freqSweepOverflow = RegInit(false.B)
  when (io.trigger) {
    freqSweepOverflow := false.B
    freqSweepShadow := io.wavelength
    freqSweepTimer := io.sweepConfig.pace
    freqSweepEnabled := (io.sweepConfig.pace =/= 0.U) || (io.sweepConfig.slope =/= 0.U)
  } .elsewhen (io.ticks.frequency && freqSweepEnabled) {
    freqSweepTimer := freqSweepTimer - 1.U
    when (freqSweepTimer === 0.U) {
      freqSweepTimer := io.sweepConfig.pace
      val offset = (freqSweepShadow >> io.sweepConfig.slope).asUInt
      val newFreq = Wire(UInt(12.W))
      when (io.sweepConfig.decrease) {
        newFreq := freqSweepShadow - offset
      } .otherwise {
        newFreq := freqSweepShadow +& offset
        when (newFreq >= 2048.U) {
          freqSweepOverflow := true.B
        }
      }

      freqSweepShadow := newFreq
      regWavelength := newFreq
    }
  }

  when (io.wavelengthLoad =/= 0.U) {
    regWavelength := Cat(
      Mux(io.wavelengthLoad(1), io.wavelength(10, 8), regWavelength(10, 8)),
      Mux(io.wavelengthLoad(0), io.wavelength(7, 0), regWavelength(7, 0)),
    )
  }

  // This channel is just the regular pulse channel with a frequency sweep unit.
  // (Is there an easier way to connect these up?)
  val pulseChannel = Module(new PulseChannel)
  pulseChannel.io.pulse4Mhz := io.pulse4Mhz
  pulseChannel.io.trigger := io.trigger
  pulseChannel.io.ticks := io.ticks
  pulseChannel.io.lengthConfig := io.lengthConfig
  pulseChannel.io.volumeConfig := io.volumeConfig
  pulseChannel.io.wavelength := regWavelength
  pulseChannel.io.duty := io.duty
  io.dacEnabled := pulseChannel.io.dacEnabled
  io.channelDisable := pulseChannel.io.channelDisable || freqSweepOverflow
  io.out := pulseChannel.io.out
}