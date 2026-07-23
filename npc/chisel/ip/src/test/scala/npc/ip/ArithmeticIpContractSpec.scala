package npc.ip

import npc.ip.arithmetic.{ArithmeticIpTiming, FloatingDirectOperator, IntegerDividerModel, IntegerMultiplierModel}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import circt.stage.ChiselStage

final class ArithmeticIpContractSpec extends AnyFlatSpec with Matchers {
  "算术 IP 契约" should "覆盖 RV32/RV64 参考端点和固定时序参数" in {
    ArithmeticIpTiming(latency = 3, initiationInterval = 2, responseFifoDepth = 4)
    Seq(32, 64).foreach { width =>
      ChiselStage.emitCHIRRTL(new IntegerMultiplierModel(width, 4, ArithmeticIpTiming(latency = 2))) should include("module IntegerMultiplierModel")
      ChiselStage.emitCHIRRTL(new IntegerDividerModel(width, 4, ArithmeticIpTiming(latency = 3))) should include("IntegerDividerModel")
      ChiselStage.emitCHIRRTL(new FloatingDirectOperator(width, 4, ArithmeticIpTiming(latency = 1))) should include("FloatingDirectOperator")
    }
  }
}
