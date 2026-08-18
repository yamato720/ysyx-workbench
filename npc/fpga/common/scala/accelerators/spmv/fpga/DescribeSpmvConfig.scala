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
import accelerators.spmv.{
  SpmvAcceleratorConfigKey,
  SpmvConstructionProfile,
  SpmvInputConfigKey,
  SpmvInputFpgaProfile,
  SpmvInputFpgaRuntimeConstruction
}
import fpga.FpgaConfigParameters

/** 为不含 CPU/NEMU 的 U55C SPMV FPGA 资产与软件 host 生成 profile。 */
object DescribeSpmvConfig extends App {
  require(args.length == 1, "用法：accelerators.spmv.fpga.DescribeSpmvConfig <profile.env>")
  val (entry, construction) = CdeConfigResolver.resolve("", Set("fpga"))
  require(entry.target == "SPMV", s"${entry.className} 不是 SPMV 终端")
  implicit val parameters: Parameters = construction
  val toolchain = construction match {
    case value: FpgaToolchainConstruction => value.fpgaToolchainConfig
    case _ => throw new IllegalArgumentException(s"${entry.className} 未挂载 FPGA 工具链")
  }
  val platform = FpgaConfigParameters.platform
  require(platform.board.name == "u55c" && entry.board.contains(platform.board.name),
    s"${entry.className} 必须选择 U55C 板卡")
  require(toolchain.device.board == platform.board.name,
    s"工具链板卡 ${toolchain.device.board} 与硬件板卡 ${platform.board.name} 不一致")
  val extra = Seq(
    "FPGA_BOARD" -> platform.board.name,
    "FPGA_CLOCK_MHZ" -> platform.clockMHz.toString,
    "FPGA_PLATFORM_CLOCK_MHZ" -> platform.platformClockMHz.toString,
    "FPGA_MEMORY_HOST_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.memoryHostBase, 16)}",
    "FPGA_CONTROL_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.controlBase, 16)}",
    "FPGA_MAILBOX_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.mailboxBase, 16)}"
  ) ++ toolchain.profileValues
  construction match {
    case runtime: SpmvInputFpgaRuntimeConstruction =>
      val input = parameters(SpmvInputConfigKey).getOrElse(
        throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvInputConfigKey")
      )
      require(platform.platformClockMHz == 300 && platform.clockMHz <= platform.platformClockMHz,
        "U55C SPMV 输入 runtime 必须保持 300 MHz DATA_CLK，核心频率不得超过它")
      ConstructionProfile.write(
        Path.of(args(0)),
        SpmvInputFpgaProfile.values(entry, runtime, input, extra)
      )
    case synthesis: FpgaSynthesisConstruction with AcceleratorHostConstruction =>
      val accelerator = parameters(SpmvAcceleratorConfigKey).getOrElse(
        throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvAcceleratorConfigKey")
      )
      require(accelerator.clockMHz == platform.clockMHz && platform.platformClockMHz == 300,
        s"SPMV kernel 时钟必须匹配 U55C core clock，platform DATA_CLK 必须为 300 MHz")
      ConstructionProfile.write(
        Path.of(args(0)),
        SpmvConstructionProfile.values(entry, synthesis, accelerator, extra)
      )
    case bitstream: FpgaBitstreamConstruction with AcceleratorHostConstruction =>
      val accelerator = parameters(SpmvAcceleratorConfigKey).getOrElse(
        throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvAcceleratorConfigKey")
      )
      require(accelerator.clockMHz == platform.clockMHz && platform.platformClockMHz == 300,
        s"SPMV kernel 时钟必须匹配 U55C core clock，platform DATA_CLK 必须为 300 MHz")
      ConstructionProfile.write(
        Path.of(args(0)),
        SpmvConstructionProfile.values(entry, bitstream, accelerator, extra)
      )
    case _ => throw new IllegalArgumentException(s"${entry.className} 未挂载 SPMV FPGA 构造终端")
  }
}
