package accelerators.spmv.fpga

import java.nio.file.Path
import org.chipsalliance.cde.config.Parameters
import npc.{
  AcceleratorHostConstruction,
  CdeConfigResolver,
  ConstructionProfile,
  FpgaBitstreamConstruction,
  FpgaSynthesisConstruction,
  FpgaToolchainConstruction
}
import accelerators.spmv.{SpmvAcceleratorConfigKey, SpmvConstructionProfile}
import fpga.FpgaConfigParameters

/** 为不含 CPU/NEMU 的 U55C SPMV FPGA 资产与软件 host 生成 profile。 */
object DescribeSpmvConfig extends App {
  require(args.length == 1, "用法：accelerators.spmv.fpga.DescribeSpmvConfig <profile.env>")
  val (entry, construction) = CdeConfigResolver.resolve("", Set("fpga"))
  require(entry.target == "SPMV", s"${entry.className} 不是 SPMV 终端")
  implicit val parameters: Parameters = construction
  val accelerator = parameters(SpmvAcceleratorConfigKey).getOrElse(
    throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvAcceleratorConfigKey")
  )
  val synthesis = construction match {
    case value: FpgaSynthesisConstruction with AcceleratorHostConstruction => value
    case value: FpgaBitstreamConstruction with AcceleratorHostConstruction => value
    case _ => throw new IllegalArgumentException(s"${entry.className} 未挂载 SPMV FPGA 构造终端")
  }
  val toolchain = construction match {
    case value: FpgaToolchainConstruction => value.fpgaToolchainConfig
    case _ => throw new IllegalArgumentException(s"${entry.className} 未挂载 FPGA 工具链")
  }
  val platform = FpgaConfigParameters.platform
  require(platform.board.name == "u55c" && entry.board.contains(platform.board.name),
    s"${entry.className} 必须选择 U55C 板卡")
  require(toolchain.device.board == platform.board.name,
    s"工具链板卡 ${toolchain.device.board} 与硬件板卡 ${platform.board.name} 不一致")
  require(accelerator.clockMHz == platform.clockMHz && platform.platformClockMHz == 300,
    s"SPMV kernel 时钟必须匹配 U55C core clock，platform DATA_CLK 必须为 300 MHz")
  val extra = Seq(
    "FPGA_BOARD" -> platform.board.name,
    "FPGA_CLOCK_MHZ" -> platform.clockMHz.toString,
    "FPGA_PLATFORM_CLOCK_MHZ" -> platform.platformClockMHz.toString,
    "FPGA_MEMORY_HOST_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.memoryHostBase, 16)}",
    "FPGA_CONTROL_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.controlBase, 16)}",
    "FPGA_MAILBOX_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.mailboxBase, 16)}"
  ) ++ toolchain.profileValues
  ConstructionProfile.write(
    Path.of(args(0)),
    SpmvConstructionProfile.values(entry, synthesis, accelerator, extra)
  )
}
