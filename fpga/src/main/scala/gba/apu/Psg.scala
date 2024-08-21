package gba.apu

import chisel3._
import chisel3.util._
import gameboy.apu.{ChannelIO, FrameSequencer, FrequencySweepConfig, PulseChannel, PulseChannelWithSweep, VolumeEnvelopeConfig}
import gba.{MMIO, MmioMap, MmioTarget}
import lib.log.Logger

/*
 * Procedural Sound Generator -- essentially the same capabilities as the Game Boy.
 *
 * Has 4 channels, each of which outputs a sample in range 0..15, corresponding to 1.0 and -1.0.
 * These get added together (if an individual channel's DAC is enabled), for a range of -4.0 to 4.0.
 * Then, each left/right channel has a volume scaler, from 1x to 8x.
 *
 * To convert an unsigned channel sample (0..15) to a signed one, we do (0xF - (2 * value)), making a range of
 * -15 to 15. With four channels, -60 to 60. With the volume scaler, -480 to 480. This is a 10-bit signed integer.
 */
class Psg extends Module {
  val io = IO(new Bundle {
    /// Global enable
    val enable = Input(Bool())

    /// MMIO access
    val mmio = new MmioTarget()

    val channelEnabled = Output(UInt(4.W))
    val volume = Input(new ApuRegisters.PsgVolume)
    val panning = Input(new ApuRegisters.PsgPanning)
    val outputLeft = Output(SInt(10.W))
    val outputRight = Output(SInt(10.W))
  })
  val logger = Logger("apu.psg", enable = io.enable)

  // General
  val regLengthEnable = RegInit(VecInit.fill(4)(false.B))
  val channelTrigger = WireDefault(VecInit.fill(4)(false.B))
  val channelEnabled = RegInit(VecInit.fill(4)(false.B))
  io.channelEnabled := channelTrigger.asUInt

  // Frame sequencer
  val frameCounter = RegInit(0.U(15.W))
  when (io.enable) {
    frameCounter := frameCounter + 1.U
  }
  val frameSequencer = Module(new FrameSequencer)
  frameSequencer.io.clockEnable := io.enable
  frameSequencer.io.divApu := frameCounter(14) // Should go from 1 -> 0, 512Hz

  // Channel 1
  val regChannel1VolumeConfig = RegInit(0.U.asTypeOf(new VolumeEnvelopeConfig))
  val regChannel1SweepConfig = RegInit(0.U.asTypeOf(new FrequencySweepConfig))
  val regChannel1Duty = RegInit(0.U(2.W))
  val regChannel1Wavelength = RegInit(0.U(11.W))
  val channel1 = Module(new PulseChannelWithSweep)
  channel1.io.lengthConfig.length := DontCare
  channel1.io.lengthConfig.lengthLoad := false.B
  channel1.io.lengthConfig.enabled := regLengthEnable(0)
  channel1.io.volumeConfig := regChannel1VolumeConfig
  channel1.io.wavelength := regChannel1Wavelength
  channel1.io.duty := regChannel1Duty
  channel1.io.sweepConfig := regChannel1SweepConfig

  // Channel 2
  val regChannel2VolumeConfig = RegInit(0.U.asTypeOf(new VolumeEnvelopeConfig))
  val regChannel2Duty = RegInit(0.U(2.W))
  val regChannel2Wavelength = RegInit(0.U(11.W))
  val channel2 = Module(new PulseChannel)
  channel2.io.lengthConfig.length := DontCare
  channel2.io.lengthConfig.lengthLoad := false.B
  channel2.io.lengthConfig.enabled := regLengthEnable(1)
  channel2.io.volumeConfig := regChannel2VolumeConfig
  channel2.io.wavelength := regChannel2Wavelength
  channel2.io.duty := regChannel2Duty

  val channel3 = Module(new NullPsgChannel)
  val channel4 = Module(new NullPsgChannel)

  // Shared channel stuff
  val channels: Seq[ChannelIO] = Seq(channel1.io, channel2.io, channel3.io, channel4.io)
  for (i <- 0 to 3) {
    channels(i).trigger := channelTrigger(i)
    channels(i).ticks := frameSequencer.io.ticks
    channels(i).pulse4Mhz := io.enable && (frameCounter(1, 0) === 0.U)
    when (io.enable && channelTrigger(i)) { channelEnabled(i) := true.B }
    when (io.enable && channels(i).channelDisable || !channels(i).dacEnabled) { channelEnabled(i) := false.B }
  }

  io.mmio <> MmioMap(
    // SOUND1CNT_L / H
    0x60 -> MmioMap.Entry(
      MmioMap.ReadFn(Cat(regChannel1VolumeConfig.asUInt, regChannel1Duty.asUInt, 0.U(15.W), regChannel1SweepConfig.asUInt)),
      MmioMap.WriteFn((enable, data, mask) => {
        when (enable) {
          when (mask(0)) {
            regChannel1SweepConfig := data(6, 0).asTypeOf(regChannel1SweepConfig)
          }
          when (mask(2)) {
            regChannel1Duty := data(23, 22)
            channel1.io.lengthConfig.length := data(21, 16)
            channel1.io.lengthConfig.lengthLoad := true.B
          }
          when (mask(3)) {
            regChannel1VolumeConfig := data(31, 24).asTypeOf(regChannel1VolumeConfig)
          }
        }
      })
    ),
    // SOUND1CNT_X
    0x64 -> MmioMap.Entry(
      MmioMap.ReadFn(Cat(regLengthEnable(0), 0.U(14.W))),
      MmioMap.WriteFn((enable, data, mask) => {
        when (enable) {
          val newWavelength = MMIO.mask(regChannel1Wavelength, data(10, 0), mask(1, 0))
          channel1.io.wavelength := newWavelength
          regChannel1Wavelength := newWavelength
          when (mask(1)) {
            regLengthEnable(0) := data(14)
            channelTrigger(0) := data(15)
          }
        }
      })
    ),
    // SOUND2CNT_L
    0x68 -> MmioMap.Entry(
      MmioMap.ReadFn(Cat(regChannel2VolumeConfig.asUInt, regChannel2Duty.asUInt, 0.U(6.W))),
      MmioMap.WriteFn((enable, data, mask) => {
        when (enable) {
          when (mask(0)) {
            regChannel2Duty := data(7, 6)
            channel2.io.lengthConfig.length := data(5, 0)
            channel2.io.lengthConfig.lengthLoad := true.B
          }
          when (mask(1)) {
            regChannel2VolumeConfig := data(15, 8).asTypeOf(regChannel2VolumeConfig)
          }
        }
      })
    ),
    // SOUND2CNT_H
    0x6C -> MmioMap.Entry(
      MmioMap.ReadFn(Cat(regLengthEnable(1), 0.U(14.W))),
      MmioMap.WriteFn((enable, data, mask) => {
        when (enable) {
          val newWavelength = MMIO.mask(regChannel2Wavelength, data(10, 0), mask(1, 0))
          channel2.io.wavelength := newWavelength
          regChannel2Wavelength := newWavelength
          when (mask(1)) {
            regLengthEnable(1) := data(14)
            channelTrigger(1) := data(15)
          }
        }
      })
    ),
  )

  // Mixing
  val dacOutput = VecInit((0 to 3).map(i =>
    Mux(channelEnabled(i), 0xF.S(5.W) - (channels(i).out << 1).asSInt, 0.S)
  ))
  val mixerLeft = VecInit((0 to 3).map(i => Mux(io.panning.left(i), dacOutput(i), 0.S))).reduceTree(_ +& _)
  val mixerRight = VecInit((0 to 3).map(i => Mux(io.panning.right(i), dacOutput(i), 0.S))).reduceTree(_ +& _)
  io.outputLeft := mixerLeft * (io.volume.volumeLeft +& 1.U).zext
  io.outputRight := mixerRight * (io.volume.volumeRight +& 1.U).zext
}
