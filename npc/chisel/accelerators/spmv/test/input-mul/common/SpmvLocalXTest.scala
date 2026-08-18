package accelerators.spmv.inputmul.common

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvInputConfig
import org.scalatest.flatspec.AnyFlatSpec

class SpmvLocalXTest extends AnyFlatSpec {
  "SpmvLocalX" should "展开 8192 列窗口、4 份双读副本和 8 个写 bank" in {
    val config = SpmvInputConfig.Cuper16Hbm
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvLocalX(config))
    val memories = "NpcOnChipTrueDualPortMemory".r.findAllMatchIn(chirrtl).size

    assert(chirrtl.contains("module SpmvLocalX"))
    assert(chirrtl.contains("writeElements"))
    assert(chirrtl.contains("writeMask"))
    assert(chirrtl.contains("readEnable"))
    assert(chirrtl.contains("readColumn"))
    assert(chirrtl.contains("readData"))
    assert(!chirrtl.contains("readReplica"))
    assert(chirrtl.contains("filled"))
    assert(chirrtl.contains("OnChipTrueDualPortMemory"))
    assert(memories >= config.xReplicaCount * config.xBankCount,
      s"应有 ${config.xReplicaCount}*${config.xBankCount} 个公共片上 RAM，实际为 $memories")
  }
}
