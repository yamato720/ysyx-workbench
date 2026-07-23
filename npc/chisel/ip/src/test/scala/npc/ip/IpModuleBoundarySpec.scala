package npc.ip

import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class IpModuleBoundarySpec extends AnyFlatSpec with Matchers {
  "独立 IP 模块" should "在不依赖 core、CDE 或 FPGA harness 时完成 elaboration" in {
    val verilog = ChiselStage.emitSystemVerilog(new IpModuleBoundary(32))
    verilog should include("module IpModuleBoundary")
    verilog should include("assign io_out = io_in")
  }
}
