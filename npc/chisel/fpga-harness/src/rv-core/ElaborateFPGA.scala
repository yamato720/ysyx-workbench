package scpu

import org.chipsalliance.cde.config.Parameters
import scpu.fpga.{CdeConfigResolver, FpgaBoard, FpgaConfigParameters, FpgaElaborationManifest}
import scpu.fpga.u55c.U55cNpcFpgaShell
import scpu.fpga.zcu102.Zcu102NpcFpgaShell

/** 生成不含 DPI、使用外部 AXI 内存和调试控制的裸核 FPGA 顶层。 */
object ElaborateFPGA extends App {
  val output = sys.props.getOrElse("npc.fpgaOutput", "./fpga/build/manual/rtl")
  val (entry, construction) = CdeConfigResolver.resolve("Zcu102NpcFpgaConfig", Set("fpga-npc"))
  implicit val parameters: Parameters = construction
  val config = FpgaConfigParameters.npcCoreConfig
  val platform = FpgaConfigParameters.platform
  val tools = FpgaConfigParameters.tools
  require(config.operators.mulDiv.implementation.backend == ComputeBackend.FPGA,
    s"${entry.className} 必须选择 FPGA 算术后端")
  require(entry.board.contains(platform.board.name),
    s"Config catalog selected ${entry.board.getOrElse("no board")}, but ${entry.className} selected ${platform.board.name}")
  println(s"正在生成 ${platform.board.name} FPGA 顶层：XLEN=${config.isa.xlen}, F=${config.isa.F}, 输出目录=$output")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    platform.board match {
      case FpgaBoard.Zcu102 => new Zcu102NpcFpgaShell
      case FpgaBoard.U55c => new U55cNpcFpgaShell
    },
    Array("--target-dir", output, "--split-verilog"),
    Array("--disable-annotation-unknown")
  )
  ElaborateOutput.stripBlackBoxFileList(s"$output/NpcFpgaTop.sv")
  FpgaElaborationManifest.write(Array("--target-dir", output), config, platform, tools, entry.className, entry.target)
}
