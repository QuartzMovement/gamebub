package lib.debug

import chisel3._
import chisel3.util._
import lib.mem.{MemoryInterface, MemoryMap, RegisterMap}
import xilinx.{XpmCdcHandshake, XpmCdcPulse, XpmCdcSingle}

object EmbeddedLogicAnalyzer {
  private case class Entry(name: String, offset: Int, width: Int)

  private def getEntries(element: Data, prefix: String = "", offset: Int = 0): Seq[Entry] = {
    element match {
      case r: Record => {
        var off = offset
        r.elements.flatMap(entry => {
          val (name, data) = entry
          val prevOffset = off
          off += data.getWidth
          getEntries(data, s"$prefix.$name", prevOffset)
        }).toSeq
      }
      case v: Vec[_] => {
        v.getElements.zipWithIndex.flatMap(entry => {
          val (data, i) = entry
          getEntries(data, s"$prefix.$i", offset + (i * data.getWidth))
        })
      }
      case _ => Seq(Entry(prefix, offset, element.getWidth))
    }
  }
}

class EmbeddedLogicAnalyzer[T <: Bundle](gen: T, depth: Int = 1024, signalClock: Option[Clock] = None) extends Module  {
  val io = IO(new Bundle {
    val interface = new MemoryInterface(addressWidth = 24, dataWidth = 32)
    val signals = Input(gen)
    val trigger = Input(Bool()) // TODO add more options for triggers
  })
  if (!isPow2(depth)) {
    throw new IllegalArgumentException(s"depth must be a power of two")
  }

  val width = gen.getWidth
  val clockSignal = signalClock.getOrElse(clock)

  private val entries = EmbeddedLogicAnalyzer.getEntries(io.signals)
  for (entry <- entries) {
    println(s"${entry.name}: offset=${entry.offset} width=${entry.width}")
  }

  // Written by outer config domain
  val armPulse = WireDefault(false.B)
  val forceTriggerPulse = WireDefault(false.B)

  // Written by inner signal domain
  val log = SRAM(depth, UInt(width.W), readPortClocks = Seq(clock), writePortClocks = Seq(clockSignal), readwritePortClocks = Seq())
  val isRecording = Wire(Bool())
  val writeIndex = Wire(UInt(24.W))
  val writeWrapped = Wire(Bool())

  withClock (clockSignal) {
    /// Whether the log has wrapped around.
    val regWriteWrapped = RegInit(false.B)
    /// Index of the next log write.
    val regWriteIndex = RegInit(0.U(log2Ceil(depth).W))
    /// Whether the log is being recorded.
    val regRecording = RegInit(true.B)

    val doArm = XpmCdcPulse(clock, armPulse)
    val doForceTrigger = XpmCdcPulse(clock, forceTriggerPulse)

    // Record into the log
    val logPort = log.writePorts(0)
    logPort.enable := regRecording
    logPort.address := regWriteIndex
    logPort.data := io.signals.asUInt
    when (regRecording) {
      val nextWriteIndex = regWriteIndex + 1.U
      regWriteIndex := nextWriteIndex
      when (nextWriteIndex === 0.U) {
        regWriteWrapped := true.B
      }
    }

    // Handle triggers
    val triggered = RegNext(io.trigger) || doForceTrigger
    when (triggered) {
      regRecording := false.B
    }

    when (doArm) {
      // Arm: start recording, and reset state
      regRecording := true.B
      regWriteIndex := 0.U
      regWriteWrapped := false.B
    }

    isRecording := withClock (clock) { XpmCdcSingle(clockSignal, regRecording) }
    writeIndex := withClock (clock) { XpmCdcHandshake.continuous(clockSignal, regWriteIndex) }
    writeWrapped := withClock (clock) { XpmCdcHandshake.continuous(clockSignal, regWriteWrapped) }
  }


  // Control interface
  val registerMap = RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries = Seq(
      // Sample width
      0x0 -> RegisterMap.Entry.r(width.U),
      // Sample depth
      0x4 -> RegisterMap.Entry.r(depth.U),
      // Log info
      0x8 -> RegisterMap.Entry.r(Cat(isRecording, writeWrapped, writeIndex)),
      // Arm
      0xC -> RegisterMap.Entry(1,
        read = RegisterMap.ReadFn(),
        write = RegisterMap.WriteFn((write: Bool, data: UInt) =>
          armPulse := write && data(0)
        ),
      ),
      // Force trigger
      0x10 -> RegisterMap.Entry(1,
        read = RegisterMap.ReadFn(),
        write = RegisterMap.WriteFn((write: Bool, data: UInt) =>
          forceTriggerPulse := write && data(0)
        ),
      ),
    )
  )

  // Log read interface
  val logReadInterface = Wire(new MemoryInterface(addressWidth = 23, dataWidth = 32))
  val logNumWords = math.ceil(width / 32.0).toInt
  val logWordBits = log2Ceil(logNumWords)
  val logReadPort = log.readPorts(0)
  val logReadWordAddress = RegNext(logReadInterface.address(logWordBits, 0))
  logReadPort.enable := logReadInterface.enable
  logReadPort.address := logReadInterface.address(22, logWordBits)
  logReadInterface.dataRead := logReadPort.data
    .pad(logNumWords * 32)
    .asTypeOf(Vec(logNumWords, UInt(32.W)))(logReadWordAddress)
  logReadInterface.done := RegNext(logReadPort.enable)

  io.interface <> MemoryMap(
    addressWidth = 24,
    dataWidth = 32,
    entries = Seq(
      "b00".U(2.W) -> registerMap,
      "b1".U(1.W) -> logReadInterface,
    ))

  // TODO: support collecting a number of samples after the trigger
  // TODO: support a series of configurable triggers (register last two values of each?)
  // TODO: output metadata (ujson? https://www.lihaoyi.com/post/HowtoworkwithJSONinScala.html)
}
