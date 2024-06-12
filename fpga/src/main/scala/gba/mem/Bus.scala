package gba.mem

import chisel3._
import chisel3.util._

class TargetInterface(maxWidth: Width) extends Bundle {
  /// Byte-wise access address
  val address = Input(UInt(25.W))
  /// Whether an access is requested
  val request = Input(Bool())
  /// Whether the access is a sequential request
  val sequential = Input(Bool())
  /// Whether the access is a write
  val write = Input(Bool())
  /// The width of the access
  val size = Input(BusAccessWidth())
  /// Byte mask strobe (if the access were aligned to 32-bits)
  val mask = Input(UInt((maxWidth.get / 8).W))
  /// Data write
  val dataWrite = Input(UInt(maxWidth))
  /// Data read
  val dataRead = Output(UInt(maxWidth))
  /// True when the access started in the previous cycle has completed
  val done = Output(Bool())
}

case class BusTarget(
  name: String,
  prefix: UInt,
  dataWidth: BusAccessWidth.Type,
)

class Bus(
  targets: Seq[BusTarget],
) extends Module {
  val io = IO(new Bundle {
    /// Global enable signal
    val enable = Input(Bool())

    /// CPU initiator port
    val initiatorPort = Flipped(new BusInterface)

    /// Target ports
    val targetPort = MixedVec(targets.map(t => Flipped(new TargetInterface(BusAccessWidth.toWidth(t.dataWidth)))))
  })

  val requestEnable = WireDefault(false.B)
  val requestAddress = Wire(UInt(28.W))
  val requestSequential = Wire(Bool())
  val requestWrite = Wire(Bool())
  val requestSize = Wire(BusAccessWidth())
  val requestDataWrite = Wire(UInt(32.W))
  val requestDataRead = Wire(UInt(32.W))
  val (requestAddressAligned, requestMask) = alignAddress(requestAddress, requestSize)
  val selectedTargetHalfword = WireDefault(false.B)

  val regAccessBusy = RegInit(false.B)
  val regAccessAddress = Reg(UInt(28.W))
  val regAccessWrite = Reg(Bool())
  val regAccessSplit = Reg(Bool())
  val regAccessSplitPhase = Reg(UInt())
  val regAccessSequential = Reg(Bool())
  val regAccessSize = Reg(BusAccessWidth())
  /// Whether the active request is completing.
  val accessDone = WireDefault(false.B)
  val regSplitBuffer = Reg(UInt(16.W))

  requestDataRead := 0.U // TODO open-bus?

  for ((target, i) <- io.targetPort.zipWithIndex) {
    val metadata = targets(i)
    val selectedNext = requestAddress(27, 27 - metadata.prefix.getWidth + 1) === metadata.prefix
    val selectedNow = regAccessAddress(27, 27 - metadata.prefix.getWidth + 1) === metadata.prefix

    target.address := requestAddressAligned
    target.request := requestEnable && selectedNext
    target.sequential := requestSequential
    target.write := requestWrite
    target.size := requestSize

    metadata.dataWidth match {
      case BusAccessWidth.Byte => {
        target.dataWrite := VecInit((0 until 4).map(i => requestDataWrite(i * 8 + 7, i * 8)))(requestAddress(1, 0))
        target.mask := 1.U
        when (selectedNow) {
          requestDataRead := Fill(4, target.dataRead)
        }
      }
      case BusAccessWidth.Halfword => {
        target.dataWrite := Mux(regAccessAddress(1), requestDataWrite(31, 16), requestDataWrite(15, 0))
        target.mask := Mux(requestAddress(1), requestMask(3, 2), requestMask(1, 0))
        when (selectedNow) {
          requestDataRead := Fill(2, target.dataRead)
        }
        when (selectedNext) {
          selectedTargetHalfword := true.B
        }
      }
      case BusAccessWidth.Word => {
        target.dataWrite := requestDataWrite
        target.mask := requestMask
        when (selectedNow) {
          requestDataRead := target.dataRead
        }
      }
    }

    when (selectedNow && regAccessBusy) {
      accessDone := target.done
    }
  }

  /// Whether there is an incoming request.
  val initiatorRequested = io.initiatorPort.MREQ
  /// Whether we can accept a new request.
  val isAvailable = (!regAccessBusy || accessDone) && (!regAccessSplit || regAccessSplitPhase === 1.U)

  requestAddress := io.initiatorPort.ADDR
  requestSequential := io.initiatorPort.SEQ // TODO multi-initiator
  requestWrite := io.initiatorPort.WRITE
  requestSize := io.initiatorPort.SIZE
  requestDataWrite := io.initiatorPort.WDATA
  io.initiatorPort.RDATA := requestDataRead
  io.initiatorPort.CLKEN := isAvailable
  io.initiatorPort.ABORT := false.B

  // During a long access, propagate the current request.
  when (regAccessBusy && !regAccessSplit && !accessDone) {
    requestEnable := true.B
    requestAddress := regAccessAddress
    requestSequential := regAccessSequential
    requestSize := regAccessSize
  }

  when (io.enable) {
    when (accessDone) {
      regAccessBusy := false.B

      when (regAccessSplit) {
        when (regAccessSplitPhase === 0.U) {
          // Start the second half.
          requestEnable := true.B
          requestAddress := regAccessAddress | 2.U
          requestSequential := true.B
          requestWrite := regAccessWrite
          requestSize := BusAccessWidth.Halfword
          requestMask := "b1111".U(4.W)
          regAccessBusy := true.B
          regAccessSplitPhase := 1.U
          regAccessAddress := requestAddress

          when (!regAccessWrite) {
            regSplitBuffer := requestDataRead
          }
        } .otherwise {
          io.initiatorPort.RDATA := Cat(requestDataRead(15, 0), regSplitBuffer)
          // io.initiatorPort.CLKEN is set above, because isAvailable is true.
        }
      }
    } .otherwise {
      when (regAccessSplit && regAccessBusy) {
        // Over a multi-cycle split access (e.g. multiple wait states within each access),
        // ensure that the access is continuously put on the target bus.
        when (regAccessSplitPhase === 0.U) {
          requestEnable := true.B
          requestSize := BusAccessWidth.Halfword
          // Ensure the initial request is aligned to the word (to handle misaligned word requests).
          requestMask := "b1111".U(4.W)
          requestAddress := regAccessAddress
          requestAddressAligned := requestAddress & "hFFFFFFFC".U(32.W)
        } .otherwise {
          requestEnable := true.B
          requestAddress := regAccessAddress | 2.U
          requestSequential := true.B
          requestWrite := regAccessWrite
          requestSize := BusAccessWidth.Halfword
          requestMask := "b1111".U(4.W)
        }
      }
    }
    when (initiatorRequested && isAvailable) {
      requestEnable := true.B
      regAccessBusy := true.B
      regAccessAddress := requestAddressAligned
      regAccessSplit := false.B
      regAccessWrite := requestWrite
      regAccessSequential := requestSequential
      regAccessSize := requestSize

      when (selectedTargetHalfword && io.initiatorPort.SIZE === BusAccessWidth.Word) {
        regAccessSplit := true.B
        regAccessSplitPhase := 0.U
        requestSize := BusAccessWidth.Halfword
        // Ensure the initial request is aligned to the word (to handle misaligned word requests).
        requestMask := "b1111".U(4.W)
        requestAddressAligned := requestAddress & "hFFFFFFFC".U(32.W)
      }
    }
  }

  def alignAddress(address: UInt, width: BusAccessWidth.Type): (UInt, UInt) = {
    val aligned = Wire(UInt(address.getWidth.W))
    when (width === BusAccessWidth.Word) {
      aligned := Cat(address(address.getWidth - 1, 2), 0.U(2.W))
    } .elsewhen (width === BusAccessWidth.Halfword) {
      aligned := Cat(address(address.getWidth - 1, 1), 0.U(1.W))
    } .otherwise {
      aligned := address
    }
    import BusAccessWidth._
    val mask = WireDefault(Cat(
      (width === Word) || (width === Halfword && address(1) === 1.U) || (width === Byte && address(1, 0) === 3.U),
      (width === Word) || (width === Halfword && address(1) === 1.U) || (width === Byte && address(1, 0) === 2.U),
      (width === Word) || (width === Halfword && address(1) === 0.U) || (width === Byte && address(1, 0) === 1.U),
      (width === Word) || (width === Halfword && address(1) === 0.U) || (width === Byte && address(1, 0) === 0.U),
    ))
    (aligned, mask)
  }

  def getMSB(input: UInt, width: Int): UInt = {
    input(input.getWidth - 1, input.getWidth - width)
  }
}
