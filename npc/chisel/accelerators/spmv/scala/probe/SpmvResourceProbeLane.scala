package accelerators.spmv.probe

import chisel3._
import chisel3.util._
import accelerators.spmv.{SpmvAcceleratorConfig, SpmvOnChipStorage}
import npc.ip.axi.Axi4ReadMasterIO

/** 单个 HBM pseudo-channel 的加载、URAM bank 写入和并行读 checksum 状态机。 */
final class SpmvResourceProbeLane(config: SpmvAcceleratorConfig) extends Module {
  require(config.storage == SpmvOnChipStorage.UltraRam)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val baseAddress = Input(UInt(config.axiAddrWidth.W))
    val axi = new Axi4ReadMasterIO(config.axiAddrWidth, config.axiDataWidth, config.axiIdWidth)
    val checksum = Output(UInt(config.elementWidth.W))
    val done = Output(Bool())
    val error = Output(Bool())
  })

  private val idle :: requestBurst :: receiveBeat :: scanCache :: finished :: Nil = Enum(5)
  private val beatIndexWidth = math.max(1, log2Ceil(config.beatsPerPc))
  private val burstIndexWidth = math.max(1, log2Ceil(config.burstsPerPc))
  private val elementIndexWidth = math.max(1, log2Ceil(config.elementsPerBeat))
  private val cacheCountWidth = math.max(1, log2Ceil(config.elementsPerPc + 1))
  private val scanCountWidth = math.max(1, log2Ceil(config.scanGroups + 1))
  private val cacheAddressWidth = math.max(1, log2Ceil(config.uramBankDepth))
  private val baseAlignmentBits = log2Ceil(config.baseAlignmentBytes)

  val state = RegInit(idle)
  val beatIndex = RegInit(0.U(beatIndexWidth.W))
  val burstIndex = RegInit(0.U(burstIndexWidth.W))
  val serializerIndex = RegInit(0.U(elementIndexWidth.W))
  val writeCount = RegInit(0.U(cacheCountWidth.W))
  val beatBuffer = Reg(UInt(config.axiDataWidth.W))
  val beatBufferValid = RegInit(false.B)
  val scanIssueCount = RegInit(0.U(scanCountWidth.W))
  val scanReturnCount = RegInit(0.U(scanCountWidth.W))
  val checksum = RegInit(0.U(config.elementWidth.W))
  val done = RegInit(false.B)
  val error = RegInit(false.B)

  private val caches = Seq.tabulate(config.uramBanksPerPc) { bank =>
    val cache = Module(new SpmvUramMemory(config.uramBankDepth, config.elementWidth))
    cache.suggestName(f"xCacheUramBank${bank}%02d")
    cache.io.clock := clock
    cache.io.aWriteEnable := false.B
    cache.io.aWriteAddress := 0.U
    cache.io.aWriteData := 0.U
    cache.io.bWriteEnable := false.B
    cache.io.bWriteAddress := 0.U
    cache.io.bWriteData := 0.U
    cache.io.aReadEnable := false.B
    cache.io.aReadAddress := 0.U
    cache.io.bReadEnable := false.B
    cache.io.bReadAddress := 0.U
    cache
  }

  io.axi.ar.valid := state === requestBurst
  io.axi.ar.bits.id := 0.U
  io.axi.ar.bits.addr := io.baseAddress + burstIndex * (config.burstBeats * config.bytesPerBeat).U
  io.axi.ar.bits.len := (config.burstBeats - 1).U
  io.axi.ar.bits.size := log2Ceil(config.bytesPerBeat).U
  io.axi.ar.bits.burst := 1.U
  io.axi.ar.bits.lock := false.B
  io.axi.ar.bits.cache := 0.U
  io.axi.ar.bits.prot := 0.U
  io.axi.ar.bits.qos := 0.U
  io.axi.r.ready := state === receiveBeat && !beatBufferValid

  io.checksum := checksum
  io.done := done
  io.error := error

  val lastBeatInBurst = if (config.burstBeats == 1) true.B else
    beatIndex(log2Ceil(config.burstBeats) - 1, 0) === (config.burstBeats - 1).U

  when((state === idle || state === finished) && io.start) {
    beatIndex := 0.U
    burstIndex := 0.U
    serializerIndex := 0.U
    writeCount := 0.U
    beatBufferValid := false.B
    scanIssueCount := 0.U
    scanReturnCount := 0.U
    checksum := 0.U
    done := false.B
    error := false.B
    when(io.baseAddress(baseAlignmentBits - 1, 0).orR) {
      error := true.B
      done := true.B
      state := finished
    }.otherwise {
      state := requestBurst
    }
  }

  when(state === requestBurst && io.axi.ar.fire) {
    state := receiveBeat
  }

  when(state === receiveBeat && io.axi.r.fire) {
    beatBuffer := io.axi.r.bits.data
    beatBufferValid := true.B
    serializerIndex := 0.U
    when(io.axi.r.bits.resp =/= 0.U || io.axi.r.bits.last =/= lastBeatInBurst) {
      error := true.B
    }
  }

  when(state === receiveBeat && beatBufferValid) {
    val elements = beatBuffer.asTypeOf(Vec(config.elementsPerBeat, UInt(config.elementWidth.W)))
    // 每个写槽根据全局序号选择 bank 和端口；八路配置一拍覆盖四个 bank 的两端口。
    for (slot <- 0 until config.writeElementsPerCycle) {
      val elementIndex = writeCount + slot.U
      val bankIndex = elementIndex % config.uramBanksPerPc.U
      val bankRow = (elementIndex / config.uramBanksPerPc.U)(cacheAddressWidth - 1, 0)
      val usePortB = (elementIndex / config.uramBanksPerPc.U) % 2.U === 1.U
      for (bank <- 0 until config.uramBanksPerPc) {
        when(bankIndex === bank.U && !usePortB) {
          caches(bank).io.aWriteEnable := true.B
          caches(bank).io.aWriteAddress := bankRow
          caches(bank).io.aWriteData := elements(serializerIndex + slot.U)
        }
        when(bankIndex === bank.U && usePortB) {
          caches(bank).io.bWriteEnable := true.B
          caches(bank).io.bWriteAddress := bankRow
          caches(bank).io.bWriteData := elements(serializerIndex + slot.U)
        }
      }
    }
    writeCount := writeCount + config.writeElementsPerCycle.U
    when(serializerIndex === (config.elementsPerBeat - config.writeElementsPerCycle).U) {
      beatBufferValid := false.B
      when(beatIndex === (config.beatsPerPc - 1).U) {
        scanIssueCount := 0.U
        scanReturnCount := 0.U
        checksum := 0.U
        state := scanCache
      }.elsewhen(lastBeatInBurst) {
        beatIndex := beatIndex + 1.U
        burstIndex := burstIndex + 1.U
        state := requestBurst
      }.otherwise {
        beatIndex := beatIndex + 1.U
      }
      }.otherwise {
        serializerIndex := serializerIndex + config.writeElementsPerCycle.U
      }
  }

  val scanReadEnable = state === scanCache && scanIssueCount < config.scanGroups.U
  if (config.readElementsPerCycle == config.uramBanksPerPc * 2) {
    for (cache <- caches) {
      cache.io.aReadEnable := scanReadEnable
      cache.io.aReadAddress := (scanIssueCount * 2.U)(cacheAddressWidth - 1, 0)
      cache.io.bReadEnable := scanReadEnable
      cache.io.bReadAddress := (scanIssueCount * 2.U + 1.U)(cacheAddressWidth - 1, 0)
    }
    val cacheReadValid = RegNext(scanReadEnable, false.B)
    val parallelData = caches.map(cache => cache.io.aReadData ^ cache.io.bReadData).reduce(_ ^ _)
    when(state === scanCache) {
      when(scanReadEnable) {
        scanIssueCount := scanIssueCount + 1.U
      }
      when(cacheReadValid) {
        checksum := checksum ^ parallelData
        scanReturnCount := scanReturnCount + 1.U
        when(scanReturnCount === (config.scanGroups - 1).U) {
          done := true.B
          state := finished
        }
      }
    }
  } else {
    val cache = caches.head
    cache.io.aReadEnable := scanReadEnable
    cache.io.aReadAddress := scanIssueCount(cacheAddressWidth - 1, 0)
    val cacheReadValid = RegNext(scanReadEnable, false.B)
    when(state === scanCache) {
      when(scanReadEnable) {
        scanIssueCount := scanIssueCount + 1.U
      }
      when(cacheReadValid) {
        checksum := checksum ^ cache.io.aReadData
        scanReturnCount := scanReturnCount + 1.U
        when(scanReturnCount === (config.scanGroups - 1).U) {
          done := true.B
          state := finished
        }
      }
    }
  }
}
