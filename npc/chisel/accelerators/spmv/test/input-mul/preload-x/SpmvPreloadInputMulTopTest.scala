package accelerators.spmv.inputmul.preload

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvInputConfig
import accelerators.spmv.inputmul.common.SpmvRequestCompletionTracker
import org.scalatest.flatspec.AnyFlatSpec

/** 检查输入层顶层展开多 HBM 输入封装、消费端和双路 X 广播状态。 */
class SpmvPreloadInputMulTopTest extends AnyFlatSpec {
  "SpmvInputTop" should "通过 A/X/Ctrl 输入封装展开当前 16+2+1 路 HBM" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvPreloadInputMulTop(SpmvInputConfig.Cuper16Hbm))

    assert(chirrtl.contains("module SpmvInputTop"))
    assert(chirrtl.contains("module SpmvAInput"))
    assert(chirrtl.contains("module SpmvXInput"))
    assert(chirrtl.contains("aInput"))
    assert(chirrtl.contains("xInput"))
    assert(chirrtl.contains("consumers_0"))
    assert(chirrtl.contains("consumers_15"))
    assert(chirrtl.contains("aHbm :"))
    assert(chirrtl.contains("xHbm :"))
    assert(chirrtl.contains("io.aHbm[0].ar"))
    assert(chirrtl.contains("io.xHbm[0].ar"))
    assert(chirrtl.contains("io.xHbm[1].ar"))
    assert(chirrtl.contains("module SpmvCtrlInput"))
    assert(chirrtl.contains("ctrlInput"))
    assert(chirrtl.contains("io.ctrlHbm[0].ar"))
    assert(chirrtl.contains("consumerABeats"))
    assert(chirrtl.contains("consumerXChecksum"))
    assert(chirrtl.contains("consumerCtrlChecksum"))
    assert(chirrtl.contains("module SpmvLocalX"))
    assert(chirrtl.contains("localXs_0"))
    assert(chirrtl.contains("localXs_15"))
    assert(chirrtl.contains("module SpmvMulEngine"))
    assert(chirrtl.contains("mulEngines_0"))
    assert(chirrtl.contains("mulEngines_15"))
    assert(chirrtl.contains("mulEnable"))
    assert(chirrtl.contains("mulBatch"))
    assert(chirrtl.contains("product"))
    assert(chirrtl.contains("stageLocalRow"))
    assert(chirrtl.contains("stageTag"))
    assert(chirrtl.contains("ctrlMapReady"))
    assert(chirrtl.contains("module SpmvCuperMap"))
    assert(chirrtl.contains("computeDone"))
    assert(chirrtl.contains("timingBeatAccepted"))
    assert(chirrtl.contains("timingChannel"))
    assert(chirrtl.contains("timingSlot"))
    assert(chirrtl.contains("timingMulRequest"))
    assert(chirrtl.contains("timingMulResponse"))
    assert(chirrtl.contains("timingValidSlotMask"))
    assert(chirrtl.contains("timingXReadMask"))
    assert(chirrtl.contains("timingMulRequestMask"))
    assert(chirrtl.contains("timingMulResponseMask"))
    assert(chirrtl.contains("timingBeatAcceptedByChannel"))
    assert(chirrtl.contains("timingValidSlotMaskByChannel"))
    assert(chirrtl.contains("timingMulRequestMaskByChannel"))
    assert(chirrtl.contains("timingMulResponseMaskByChannel"))
    assert(chirrtl.contains("timingComputeDoneByChannel"))
    assert(chirrtl.contains("streamsComplete"))
    assert(chirrtl.contains("module SpmvRequestCompletionTracker"))
    assert(chirrtl.contains("aCompletion"))
    assert(chirrtl.contains("mulProductChecksum"))
    assert(chirrtl.contains("module SpmvFp32ToFp64"))
    assert(chirrtl.contains("module SpmvFp64Mul"))
    assert(!chirrtl.contains("SpmvFp64Add"))
  }

  it should "等待每路单遍 A 请求均已接受并完成" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvRequestCompletionTracker(4))

    assert(chirrtl.contains("module SpmvRequestCompletionTracker"))
    assert(chirrtl.contains("requestAccepted"))
    assert(chirrtl.contains("active"))
    assert(chirrtl.contains("requestSeen"))
    assert(chirrtl.contains("complete"))
  }

}
