package accelerators.spmv.inputmul.cuperflow

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvCuperflowConfig
import org.scalatest.flatspec.AnyFlatSpec

class SpmvCuperflowLocalXTest extends AnyFlatSpec {
  "Cuperflow local_X" should "展开两个 ping/pong bank 和每 bank 四份真双口 replica" in {
    val config = SpmvCuperflowConfig.Simulation
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvCuperflowLocalX(config))
    val memories = "NpcOnChipMaskedTrueDualPortMemory".r.findAllMatchIn(chirrtl).size

    assert(chirrtl.contains("module SpmvCuperflowLocalX"))
    assert(chirrtl.contains("loadBank"))
    assert(chirrtl.contains("activate"))
    assert(chirrtl.contains("activeBank"))
    assert(chirrtl.contains("writeIdle"))
    assert(chirrtl.contains("readColumn"))
    assert(chirrtl.contains("OnChipMaskedTrueDualPortMemory"))
    assert(chirrtl.contains("wmask"))
    assert(memories >= 2 * config.xReplicaCount,
      s"应有 2*${config.xReplicaCount} 个片上 RAM，实际为 $memories")
  }
}
