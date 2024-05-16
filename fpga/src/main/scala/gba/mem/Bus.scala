package gba.mem

import chisel3._
import chisel3.util._
import gba.cpu.{BusAccessWidth, BusInterface, BusTransactionType}

class TargetInterface(maxWidth: BusAccessWidth.Type) extends Bundle {
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
  val mask = Input(UInt(4.W))
  /// Data write
  val dataWrite = Input(UInt(BusAccessWidth.toWidth(maxWidth)))
  /// Data read
  val dataRead = Output(UInt(BusAccessWidth.toWidth(maxWidth)))
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
    val targetPort = MixedVec(targets.map(t => Flipped(new TargetInterface(t.dataWidth))))
  })

  val requestEnable = WireDefault(false.B)
  val requestAddress = Wire(UInt(28.W))
  val requestSequential = Wire(Bool())
  val requestWrite = Wire(Bool())
  val requestSize = Wire(BusAccessWidth())
  val requestDataWrite = Wire(UInt(32.W))
  val requestDataRead = Wire(UInt(32.W))
  val (requestAddressAligned, requestMask) = alignAddress(requestAddress, requestSize)

  val regAccessBusy = RegInit(false.B)
  val regAccessAddress = Reg(UInt(28.W))
  /// Whether the active request is completing.
  val accessDone = WireDefault(false.B)

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
    target.dataWrite := io.initiatorPort.WDATA
    target.mask := requestMask

    metadata.dataWidth match {
      case BusAccessWidth.Byte => {
        target.dataWrite := VecInit((0 until 4).map(i => requestDataWrite(i * 8 + 7, i * 8)))(requestAddress(1, 0))
        when (selectedNow) {
          requestDataRead := Fill(4, target.dataRead)
        }
      }
      case BusAccessWidth.Halfword => {
        target.dataWrite := Mux(requestAddress(1), requestDataWrite(31, 16), requestDataWrite(15, 0))
        when (selectedNow) {
          requestDataRead := Fill(2, target.dataRead)
        }
      }
      case BusAccessWidth.Word => {
        target.dataWrite := requestDataWrite
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
  val initiatorRequested =
    (io.initiatorPort.TRANS === BusTransactionType.Sequential ||
      io.initiatorPort.TRANS === BusTransactionType.NonSequential)
  /// Whether we can accept a new request.
  val isAvailable = !regAccessBusy || accessDone

  when (io.enable) {
    when (accessDone) {
      regAccessBusy := false.B
    }
    when (initiatorRequested && isAvailable) {
      requestEnable := true.B
      regAccessBusy := true.B
      regAccessAddress := requestAddress
    }
  }

  requestAddress := io.initiatorPort.ADDR
  requestSequential := io.initiatorPort.TRANS === BusTransactionType.Sequential // TODO multi-initiator
  requestWrite := io.initiatorPort.WRITE
  requestSize := io.initiatorPort.SIZE
  requestDataWrite := io.initiatorPort.WDATA
  io.initiatorPort.RDATA := requestDataRead
  io.initiatorPort.CLKEN := isAvailable
  io.initiatorPort.ABORT := false.B

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
    val mask = Cat(
      (width === Word) || (width === Halfword && address(1) === 1.U) || (width === Byte && address(1, 0) === 3.U),
      (width === Word) || (width === Halfword && address(1) === 1.U) || (width === Byte && address(1, 0) === 2.U),
      (width === Word) || (width === Halfword && address(1) === 0.U) || (width === Byte && address(1, 0) === 1.U),
      (width === Word) || (width === Halfword && address(1) === 0.U) || (width === Byte && address(1, 0) === 0.U),
    )
    (aligned, mask)
  }

  def getMSB(input: UInt, width: Int): UInt = {
    input(input.getWidth - 1, input.getWidth - width)
  }
}
