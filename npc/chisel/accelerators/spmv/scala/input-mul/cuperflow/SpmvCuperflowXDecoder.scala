package accelerators.spmv.inputmul.cuperflow

import accelerators.spmv.SpmvCuperflowConfig
import chisel3._
import chisel3.util.{Decoupled, log2Ceil}

/** 一个 512-bit Cuperflow X beat 的最多八笔 local-X 写入。 */
final class SpmvCuperflowXWriteBatch(config: SpmvCuperflowConfig) extends Bundle {
  val valid = Vec(config.xWordsPerBeat, Bool())
  val address = Vec(config.xWordsPerBeat, UInt(log2Ceil(config.xWindowSize).W))
  val data = Vec(config.xWordsPerBeat, UInt(config.xElementWidth.W))
}

/** 与 C++ `makeXAddressMarker`/`isXAddressMarker` 共享的地址 token 位型。 */
object SpmvCuperflowXAddressMarker {
  val addressBits: Int = 13
  val addressMask: BigInt = (BigInt(1) << addressBits) - 1
  val prefix: BigInt =
    (BigInt(0x7ff) << 52) |
      (BigInt(1) << 51) |
      (BigInt(1) << 48) |
      (BigInt(0x1a5a5) << addressBits)
  val fixedPrefix: BigInt = prefix >> addressBits

  def marker(address: Int): BigInt = {
    require(address >= 0 && BigInt(address) <= addressMask,
      s"Cuperflow X marker 地址必须位于 13 bit 范围，实际为 $address")
    prefix | BigInt(address)
  }

  def isMarker(word: UInt): Bool =
    word(63, addressBits) === fixedPrefix.U((64 - addressBits).W)

  def isMarkerBits(word: BigInt): Boolean =
    (word & ~addressMask) == prefix
}

/** 每拍解析一个 512-bit X beat 的八路 Cuperflow marker decoder。
  *
  * marker 的地址依赖前一 token，因此八路并非八套互不相关的状态机，而是对同一 beat
  * 做固定八级 prefix scan：lane `n` 的写地址使用 lane `0..n-1` 解析后的地址状态。
  * 普通 value 最多生成八笔写入，随后由 packed local-X 合并为一至多笔带 mask 的 line 写。
  */
final class SpmvCuperflowXDecoder8(config: SpmvCuperflowConfig) extends Module {
  private val addressBits = log2Ceil(config.xWindowSize)
  private val wordsPerBeat = config.xWordsPerBeat
  private val wordCountWidth = log2Ceil(wordsPerBeat + 1)
  require(addressBits == SpmvCuperflowXAddressMarker.addressBits,
    s"Cuperflow X marker 地址宽度必须为 ${SpmvCuperflowXAddressMarker.addressBits}，实际为 $addressBits")

  val io = IO(new Bundle {
    /** 每个 work 的起点清除地址状态和尚未提交的一个 beat。 */
    val clear = Input(Bool())
    /** 当前 X range 的逻辑容量；最后一个 range 可以小于 8192。 */
    val rangeElements = Input(UInt(log2Ceil(config.xWindowSize + 1).W))
    val input = Flipped(Decoupled(new Bundle {
      val data = UInt(config.axiDataWidth.W)
      /** 尾 beat 只解析前 `validWords` 个 token，忽略传输对齐填充。 */
      val validWords = UInt(wordCountWidth.W)
      val last = Bool()
    }))
    val write = Decoupled(new SpmvCuperflowXWriteBatch(config))
    val nextAddress = Output(UInt(addressBits.W))
    val error = Output(Bool())
  })

  private val nextAddress = RegInit(0.U(addressBits.W))
  private val pendingValid = RegInit(false.B)
  private val pendingWrite = Reg(new SpmvCuperflowXWriteBatch(config))
  private val error = RegInit(false.B)
  private val words = io.input.bits.data.asTypeOf(Vec(wordsPerBeat, UInt(config.xElementWidth.W)))

  private val decodedWrite = Wire(new SpmvCuperflowXWriteBatch(config))
  private var runningAddress: UInt = nextAddress
  private var inputError: Bool = io.input.bits.validWords === 0.U ||
    io.input.bits.validWords > wordsPerBeat.U
  for (lane <- 0 until wordsPerBeat) {
    val laneValid = lane.U < io.input.bits.validWords
    val word = words(lane)
    val marker = SpmvCuperflowXAddressMarker.isMarker(word)
    val markerAddress = word(addressBits - 1, 0)
    val currentAddress = runningAddress
    val isLastWord = io.input.bits.last && io.input.bits.validWords === (lane + 1).U

    decodedWrite.valid(lane) := laneValid && !marker
    decodedWrite.address(lane) := currentAddress
    decodedWrite.data(lane) := word
    inputError = inputError || (laneValid && Mux(marker,
      markerAddress >= io.rangeElements || isLastWord,
      currentAddress >= io.rangeElements))
    runningAddress = Mux(laneValid, Mux(marker, markerAddress, currentAddress + 1.U), currentAddress)
  }

  io.input.ready := !io.clear && (!pendingValid || io.write.ready)
  io.write.valid := pendingValid
  io.write.bits := pendingWrite
  io.nextAddress := nextAddress
  io.error := error

  when(io.clear) {
    nextAddress := 0.U
    pendingValid := false.B
    error := false.B
  }.otherwise {
    when(io.write.fire) {
      pendingValid := false.B
    }
    when(io.input.fire) {
      nextAddress := runningAddress
      pendingWrite := decodedWrite
      pendingValid := true.B
      when(inputError) {
        error := true.B
      }
    }
  }
}
