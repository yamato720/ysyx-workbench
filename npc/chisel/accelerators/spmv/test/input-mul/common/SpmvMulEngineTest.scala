package accelerators.spmv.inputmul.common

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvInputConfig
import org.scalatest.flatspec.AnyFlatSpec

class SpmvMulEngineTest extends AnyFlatSpec {
  "SpmvMulEngine" should "展开 8-lane Mixed-V3 FP64 乘法流水和乘积校验" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvMulEngine(SpmvInputConfig.Cuper16Hbm))

    assert(chirrtl.contains("module SpmvMulEngine"))
    assert(chirrtl.contains("computeDone"))
    assert(chirrtl.contains("streamsComplete"))
    assert(chirrtl.contains("workExpected"))
    assert(chirrtl.contains("started"))
    assert(chirrtl.contains("product :"))
    assert(chirrtl.contains("productChecksum"))
    assert(chirrtl.contains("stageLocalRow"))
    assert(chirrtl.contains("stageTag"))
    assert(chirrtl.contains("stageBatch"))
    assert(chirrtl.contains("xReadColumn"))
    assert(chirrtl.contains("timingBeatAccepted"))
    assert(chirrtl.contains("timingSlot"))
    assert(chirrtl.contains("timingDecode"))
    assert(chirrtl.contains("timingXRead"))
    assert(chirrtl.contains("timingMulRequest"))
    assert(chirrtl.contains("timingMulResponse"))
    assert(chirrtl.contains("timingValidSlotMask"))
    assert(chirrtl.contains("timingXReadMask"))
    assert(chirrtl.contains("timingMulRequestMask"))
    assert(chirrtl.contains("timingMulResponseMask"))
    assert(chirrtl.contains("timingStreamsComplete"))
    assert(chirrtl.contains("extmodule SpmvFp32ToFp64") || chirrtl.contains("module SpmvFp32ToFp64"))
    assert(chirrtl.contains("module SpmvFp64Mul"))
    assert(chirrtl.contains("module ArithmeticIpModel") || chirrtl.contains("responseQueue"))
    assert(chirrtl.contains("SpmvFp64MulSimulation"))
    assert(chirrtl.contains("row"))
    assert(chirrtl.contains("tag"))
    assert(chirrtl.contains("batch"))
    assert(chirrtl.contains("pe"))
    assert(chirrtl.contains("lane"))
    assert(chirrtl.contains("stageValidMask"))
    assert(!chirrtl.contains("SpmvFp64Add"))
    assert(!chirrtl.contains("yReadData"))
  }
}
