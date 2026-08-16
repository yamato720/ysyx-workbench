package npc

import org.chipsalliance.cde.config.Parameters
import npc.{CdeConfigResolver, FpgaConstruction}
import _root_.fpga.{FpgaBoard, FpgaConfigParameters, FpgaElaborationManifest}
import npc.fpga.u55c.U55cNpcFpgaShell
import npc.fpga.zcu102.Zcu102NpcFpgaShell

/** 生成不含 DPI、使用外部 AXI 内存和调试控制的裸核 FPGA 顶层。 */
object ElaborateFPGA extends App {
  val output = args.sliding(2).collectFirst {
    case Array("--target-dir", directory) => directory
  }.orElse(sys.props.get("npc.fpgaOutput")).getOrElse("./fpga/build/manual/rtl")
  val (entry, construction) = CdeConfigResolver.resolve("Zcu102NpcFpgaConfig", Set("fpga"))
  require(entry.target == "NPC", s"${entry.className} 不是裸 NPC FPGA Config")
  implicit val parameters: Parameters = construction
  val config = FpgaConfigParameters.npcCoreConfig
  val platform = FpgaConfigParameters.platform
  val performanceMonitor = FpgaConfigParameters.performanceMonitor
  val runtimeSdb = FpgaConfigParameters.runtimeSdb
  val ipAttachment = FpgaConfigParameters.ipAttachment
  val toolchain = construction match {
    case value: FpgaConstruction => value.fpgaToolchainConfig
    case _ => throw new IllegalArgumentException(s"${entry.className} 未挂载 FPGA 终端 trait")
  }
  require(config.operators.mulDiv.implementation.backend == ComputeBackend.FPGA,
    s"${entry.className} 必须选择 FPGA 算术后端")
  require(entry.board.contains(platform.board.name),
    s"Config catalog selected ${entry.board.getOrElse("no board")}, but ${entry.className} selected ${platform.board.name}")
  require(toolchain.device.board == platform.board.name,
    s"FPGA toolchain selected ${toolchain.device.board}, but hardware CDE selected ${platform.board.name}")
  require(!performanceMonitor.enabled || platform.board == FpgaBoard.U55c,
    s"${entry.className} 的 FPGA 性能监测只支持 U55C")
  println(s"正在生成 ${platform.board.name} FPGA 顶层：XLEN=${config.isa.xlen}, 输出目录=$output")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    platform.board match {
      case FpgaBoard.Zcu102 => new Zcu102NpcFpgaShell
      case FpgaBoard.U55c => new U55cNpcFpgaShell
    },
    Array("--target-dir", output, "--split-verilog"),
    Array("--disable-annotation-unknown")
  )
  ElaborateOutput.stripBlackBoxFileList(s"$output/NpcFpgaTop.sv")
  FpgaElaborationManifest.write(
    Array("--target-dir", output), config, platform, performanceMonitor, runtimeSdb,
    ipAttachment, toolchain, entry.className, entry.target)
}
