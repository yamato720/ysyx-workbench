package npc

import org.chipsalliance.cde.config.{Config => CDEConfig, Field}

sealed trait SpmvOnChipStorage { def name: String }

object SpmvOnChipStorage {
  case object UltraRam extends SpmvOnChipStorage { override val name: String = "uram" }
}

/** SPMV 资源探针和后续正式加速器共享的静态硬件参数。 */
final case class SpmvAcceleratorConfig(
  hbmPcCount: Int,
  axiAddrWidth: Int,
  axiDataWidth: Int,
  axiIdWidth: Int,
  elementWidth: Int,
  elementsPerPc: Int,
  readElementsPerCycle: Int,
  storage: SpmvOnChipStorage,
  burstBeats: Int,
  outstandingBurstsPerPc: Int,
  clockMHz: Int,
  /** 每个 PC 的 URAM bank 数；每个 bank 固定使用两个独立端口。 */
  uramBanksPerPc: Int = 1,
  /** 每拍写入 cache 的元素数，必须是串行 1 项或占满所有 URAM 端口。 */
  writeElementsPerCycle: Int = 1
) {
  require(hbmPcCount > 0 && hbmPcCount <= 32,
    s"SPMV HBM PC count must be in 1..32, got $hbmPcCount")
  require(axiAddrWidth == 64,
    s"SPMV HBM AXI address width must be 64, got $axiAddrWidth")
  require(axiDataWidth >= 64 && PowerOfTwo(axiDataWidth) && axiDataWidth % 8 == 0,
    s"SPMV HBM AXI data width must be a byte-aligned power of two, got $axiDataWidth")
  require(axiIdWidth > 0,
    s"SPMV HBM AXI ID width must be positive, got $axiIdWidth")
  require(elementWidth == 32 || elementWidth == 64,
    s"SPMV element width must be 32 or 64, got $elementWidth")
  require(axiDataWidth % elementWidth == 0,
    s"SPMV AXI width $axiDataWidth must contain whole $elementWidth-bit elements")
  require(PowerOfTwo(elementsPerPc),
    s"SPMV elements per PC must be a positive power of two, got $elementsPerPc")
  require(uramBanksPerPc > 0 && PowerOfTwo(uramBanksPerPc),
    s"SPMV URAM bank count must be a positive power of two, got $uramBanksPerPc")
  require(elementsPerPc % uramBanksPerPc == 0,
    s"SPMV elements per PC $elementsPerPc must divide evenly across $uramBanksPerPc URAM banks")
  require(readElementsPerCycle > 0 && readElementsPerCycle <= uramBanksPerPc * 2,
    s"SPMV read bandwidth must fit two ports per bank, got $readElementsPerCycle for $uramBanksPerPc banks")
  require(readElementsPerCycle == 1 || readElementsPerCycle == uramBanksPerPc * 2,
    s"SPMV read bandwidth must be serial or use every URAM port, got $readElementsPerCycle")
  require(writeElementsPerCycle > 0 && writeElementsPerCycle <= uramBanksPerPc * 2,
    s"SPMV write bandwidth must fit two ports per bank, got $writeElementsPerCycle for $uramBanksPerPc banks")
  require(writeElementsPerCycle == 1 || writeElementsPerCycle == uramBanksPerPc * 2,
    s"SPMV write bandwidth must be serial or use every URAM port, got $writeElementsPerCycle")
  require(storage == SpmvOnChipStorage.UltraRam,
    s"SPMV resource probe requires UltraRAM storage, got ${storage.name}")
  require(burstBeats > 0 && burstBeats <= 256 && PowerOfTwo(burstBeats),
    s"SPMV burst beats must be a power of two in 1..256, got $burstBeats")
  require(outstandingBurstsPerPc == 1,
    s"SPMV resource probe currently supports one outstanding burst per PC, got $outstandingBurstsPerPc")
  require(clockMHz > 0, s"SPMV clock must be positive, got $clockMHz MHz")

  val elementsPerBeat: Int = axiDataWidth / elementWidth
  require(elementsPerPc % elementsPerBeat == 0,
    s"SPMV elements per PC $elementsPerPc must fill whole AXI beats of $elementsPerBeat elements")
  require(elementsPerBeat % writeElementsPerCycle == 0,
    s"SPMV AXI beat width $elementsPerBeat must divide write group $writeElementsPerCycle")
  require(elementsPerPc % readElementsPerCycle == 0,
    s"SPMV elements per PC $elementsPerPc must divide read group $readElementsPerCycle")
  val beatsPerPc: Int = elementsPerPc / elementsPerBeat
  require(beatsPerPc % burstBeats == 0,
    s"SPMV beats per PC $beatsPerPc must be divisible by burst length $burstBeats")

  val bytesPerBeat: Int = axiDataWidth / 8
  val burstBytes: Int = burstBeats * bytesPerBeat
  require(burstBytes <= 4096,
    s"SPMV AXI burst must fit within one 4 KiB boundary, got $burstBytes bytes")
  val baseAlignmentBytes: Int = burstBytes
  val bytesPerPc: Int = elementsPerPc * (elementWidth / 8)
  val burstsPerPc: Int = beatsPerPc / burstBeats
  val uramBankDepth: Int = elementsPerPc / uramBanksPerPc
  val scanGroups: Int = elementsPerPc / readElementsPerCycle
  val totalCacheBytes: Long = hbmPcCount.toLong * bytesPerPc
}

object SpmvAcceleratorConfig {
  val U55c32PcFp32X8192Uram: SpmvAcceleratorConfig = SpmvAcceleratorConfig(
    hbmPcCount = 32,
    axiAddrWidth = 64,
    axiDataWidth = 512,
    axiIdWidth = 4,
    elementWidth = 32,
    elementsPerPc = 8192,
    readElementsPerCycle = 1,
    storage = SpmvOnChipStorage.UltraRam,
    burstBeats = 64,
    outstandingBurstsPerPc = 1,
    clockMHz = 300
  )

  /** U55C 32-PC FP64 探针：四个双端口 bank 每拍并行读写八个 X。 */
  val U55c32PcFp64X8192Uram8Lane: SpmvAcceleratorConfig = SpmvAcceleratorConfig(
    hbmPcCount = 32,
    axiAddrWidth = 64,
    axiDataWidth = 512,
    axiIdWidth = 4,
    elementWidth = 64,
    elementsPerPc = 8192,
    readElementsPerCycle = 8,
    storage = SpmvOnChipStorage.UltraRam,
    burstBeats = 64,
    outstandingBurstsPerPc = 1,
    clockMHz = 225,
    uramBanksPerPc = 4,
    writeElementsPerCycle = 8
  )
}

case object SpmvAcceleratorConfigKey extends Field[Option[SpmvAcceleratorConfig]](None)

class WithSpmvAcceleratorConfig(config: SpmvAcceleratorConfig) extends CDEConfig((_, _, _) => {
  case SpmvAcceleratorConfigKey => Some(config)
})
