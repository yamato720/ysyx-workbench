package ysyx

import org.chipsalliance.cde.config.Parameters
import _root_.scpu.FpgaConstruction
import _root_.scpu.fpga.{CdeConfigResolver, FpgaBoard, FpgaConfigParameters, FpgaElaborationManifest}
import _root_.scpu.ComputeBackend
import ysyx.fpga.u55c.U55cYsyxFpgaShell
import ysyx.fpga.zcu102.Zcu102YsyxFpgaShell

object ElaborateFPGA extends App {
  val (entry, construction) = CdeConfigResolver.resolve("Zcu102YsyxSocFpgaConfig", Set("fpga"))
  require(entry.target == "SOC", s"${entry.className} 不是 ysyxSoC FPGA Config")
  implicit val parameters: Parameters = construction
  val npcConfig = FpgaConfigParameters.npcCoreConfig
  val platform = FpgaConfigParameters.platform
  val toolchain = construction match {
    case value: FpgaConstruction => value.fpgaToolchainConfig
    case _ => throw new IllegalArgumentException(s"${entry.className} 未挂载 FPGA 终端 trait")
  }
  require(npcConfig.operators.mulDiv.implementation.backend == ComputeBackend.FPGA,
    s"${entry.className} 必须选择 FPGA 算术后端")
  require(entry.board.contains(platform.board.name),
    s"Config catalog selected ${entry.board.getOrElse("no board")}, but ${entry.className} selected ${platform.board.name}")
  require(toolchain.device.board == platform.board.name,
    s"FPGA toolchain selected ${toolchain.device.board}, but hardware CDE selected ${platform.board.name}")
  val firtoolOptions = Array("--disable-annotation-unknown")
  // Chisel 7.0.0-M2 does not enable split output in emitSystemVerilogFile.
  // Keep each generated module in its own source file for Vivado/Vitis.
  circt.stage.ChiselStage.emitSystemVerilogFile(
    platform.board match {
      case FpgaBoard.Zcu102 => new Zcu102YsyxFpgaShell
      case FpgaBoard.U55c => new U55cYsyxFpgaShell
    },
    args ++ Array("--split-verilog"),
    firtoolOptions
  )
  FpgaElaborationManifest.write(args, npcConfig, platform, toolchain, entry.className, entry.target)
}
