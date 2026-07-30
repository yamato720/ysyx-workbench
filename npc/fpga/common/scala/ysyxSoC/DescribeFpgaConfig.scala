package ysyx

import java.nio.file.Path
import org.chipsalliance.cde.config.Parameters
import _root_.npc.{CdeConfigResolver, ConstructionProfile, FpgaConstruction, HostConstruction}
import _root_.npc.fpga.FpgaConfigParameters

/** Generates the profile for board-backed ysyxSoC or bare-NPC terminals. */
object DescribeFpgaConfig extends App {
  require(args.length == 1, "usage: ysyx.DescribeFpgaConfig <profile.env>")
  val (entry, construction) = CdeConfigResolver.resolve("", Set("fpga"))
  val metadata: HostConstruction = construction
  implicit val parameters: Parameters = construction
  val platform = FpgaConfigParameters.platform
  val performanceMonitor = FpgaConfigParameters.performanceMonitor
  val runtimeSdb = FpgaConfigParameters.runtimeSdb
  val ipAttachment = FpgaConfigParameters.ipAttachment
  val fpga = construction match {
    case value: FpgaConstruction => value
    case _ => throw new IllegalArgumentException(s"${entry.className} is missing an FPGA terminal trait")
  }
  val toolchain = fpga.fpgaToolchainConfig
  require(entry.board.contains(toolchain.device.board),
    s"catalog board ${entry.board.getOrElse("none")} does not match toolchain board ${toolchain.device.board}")
  require(platform.board.name == toolchain.device.board,
    s"hardware board ${platform.board.name} does not match toolchain board ${toolchain.device.board}")
  require(!performanceMonitor.enabled ||
    (entry.target == "NPC" && platform.board.name == "u55c"),
    s"${entry.className} 的 FPGA 性能监测只支持 U55C 裸 NPC")
  require(!(performanceMonitor.enabled && runtimeSdb.enabled),
    s"${entry.className} 不能同时启用性能监测和 SDB")
  val extra = Seq(
    "FPGA_BOARD" -> platform.board.name,
    "FPGA_CLOCK_MHZ" -> platform.clockMHz.toString,
    "FPGA_PLATFORM_CLOCK_MHZ" -> platform.platformClockMHz.toString,
    "FPGA_MEMORY_HOST_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.memoryHostBase, 16)}",
    "FPGA_CONTROL_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.controlBase, 16)}",
    "FPGA_MAILBOX_BASE" -> s"0x${java.lang.Long.toUnsignedString(platform.mailboxBase, 16)}"
  ) ++ ipAttachment.manifestValues ++ toolchain.profileValues
  ConstructionProfile.write(
    Path.of(args(0)),
    ConstructionProfile.values(entry, metadata, FpgaConfigParameters.npcCoreConfig, extra,
      performanceMonitor.profile, runtimeSdb.enabled)
  )
}
