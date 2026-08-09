package spmv

import java.nio.file.Path
import org.chipsalliance.cde.config.Parameters
import npc.{
  ConstructionProfile,
  SpmvConfigResolver,
  SpmvCsr5MulConfigKey,
  SpmvSimulationConstruction,
  SpmvSimulationProfile
}

/** 为独立 CSR5 Verilator 终端生成不含 CPU/NEMU 字段的 profile。 */
object DescribeSpmvSimulationConfig extends App {
  require(args.length == 1, "用法：spmv.DescribeSpmvSimulationConfig <profile.env>")
  val (entry, construction) = SpmvConfigResolver.resolve("")
  val simulation = construction match {
    case value: SpmvSimulationConstruction => value
    case _ => throw new IllegalArgumentException(s"${entry.className} 未挂载 SPMV 仿真构造")
  }
  implicit val parameters: Parameters = construction
  val accelerator = parameters(SpmvCsr5MulConfigKey).getOrElse(
    throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvCsr5MulConfigKey")
  )
  ConstructionProfile.write(
    Path.of(args(0)),
    SpmvSimulationProfile.values(entry, simulation, accelerator)
  )
}
