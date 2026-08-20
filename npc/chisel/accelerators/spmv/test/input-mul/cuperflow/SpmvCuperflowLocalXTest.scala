package accelerators.spmv.inputmul.cuperflow

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvCuperflowConfig
import org.scalatest.flatspec.AnyFlatSpec

class SpmvCuperflowLocalXTest extends AnyFlatSpec {
  "Cuperflow local_X" should "默认展开四份真双口 replica 的单窗口" in {
    val config = SpmvCuperflowConfig.Simulation
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvCuperflowLocalX(config))
    val memories = "NpcOnChipMaskedTrueDualPortMemory".r.findAllMatchIn(chirrtl).size

    assert(!config.xPingPong)
    assert(config.xBankCount == 1)
    assert(chirrtl.contains("module SpmvCuperflowLocalX"))
    assert(chirrtl.contains("writeIdle"))
    assert(chirrtl.contains("readColumn"))
    assert(chirrtl.contains("OnChipMaskedTrueDualPortMemory"))
    assert(chirrtl.contains("wmask"))
    assert(chirrtl.contains("module SpmvCuperflowIssuedXWriteStage"))
    assert(chirrtl.contains("issuedSequentialWrite_r0"))
    assert(chirrtl.contains("issuedSequentialWrite_r3"))
    assert(!chirrtl.contains("issuedSequentialWrite_b0_r0"))
    assert(!chirrtl.contains("PriorityEncoder"))
    assert(chirrtl.contains("DontTouchAnnotation"))
    assert(memories >= config.xReplicaCount,
      s"应有 ${config.xReplicaCount} 个片上 RAM，实际为 $memories")
    val issuedStages = "issuedSequentialWrite_r[0-3]".r.findAllMatchIn(chirrtl).map(_.matched).toSet
    assert(issuedStages.size == config.xReplicaCount,
      s"每个物理 URAM 应有一份 issued 写级，实际为 $issuedStages")
  }

  it should "在 xPingPong 下展开两套 bank 和每 bank 四份 replica" in {
    val config = SpmvCuperflowConfig.Simulation.copy(xPingPong = true)
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvCuperflowLocalX(config))
    val memories = "NpcOnChipMaskedTrueDualPortMemory".r.findAllMatchIn(chirrtl).size

    assert(config.xBankCount == 2)
    assert(chirrtl.contains("issuedSequentialWrite_b0_r0"))
    assert(chirrtl.contains("issuedSequentialWrite_b1_r3"))
    assert(memories >= config.xBankCount * config.xReplicaCount,
      s"应有 ${config.xBankCount}*${config.xReplicaCount} 个片上 RAM，实际为 $memories")
    val issuedStages = "issuedSequentialWrite_b[01]_r[0-3]".r.findAllMatchIn(chirrtl).map(_.matched).toSet
    assert(issuedStages.size == config.xBankCount * config.xReplicaCount,
      s"每个物理 URAM 应有一份 issued 写级，实际为 $issuedStages")
  }
}
