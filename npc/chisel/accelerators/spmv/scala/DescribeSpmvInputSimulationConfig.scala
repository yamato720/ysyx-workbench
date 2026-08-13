package accelerators.spmv

import java.nio.file.Path
import org.chipsalliance.cde.config.{Config => CDEConfig}
import org.chipsalliance.cde.config.Parameters
import npc.{
  ConstructionProfile,
  ConfigCatalog,
  MakeTerminal
}

/** 为独立 SPMV 正式输入终端生成不含 CPU/NEMU 字段的 profile。 */
object DescribeSpmvInputSimulationConfig extends App {
  require(args.length == 1,
    "用法：accelerators.spmv.DescribeSpmvInputSimulationConfig <profile.env>")
  val requested = ConfigCatalog.selectedName("")
  val entry = ConfigCatalog.resolve(requested, Set("spmv"))
  val construction = try {
    Class.forName(entry.className).getDeclaredConstructor().newInstance().asInstanceOf[CDEConfig]
  } catch {
    case error: ReflectiveOperationException =>
      throw new IllegalArgumentException(s"无法构造 SPMV 配置 ${entry.className}：${error.getMessage}", error)
  }
  val simulation = construction match {
    case value: CDEConfig with SpmvInputSimulationConstruction with MakeTerminal => value
    case _ => throw new IllegalArgumentException(s"${entry.className} 未挂载 SPMV 正式输入构造")
  }
  implicit val parameters: Parameters = construction
  val input = parameters(SpmvInputConfigKey).getOrElse(
    throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvInputConfigKey")
  )
  val report = parameters(SpmvInputReportConfigKey)
  ConstructionProfile.write(
    Path.of(args(0)),
    SpmvInputSimulationProfile.values(entry, simulation, input, report)
  )
}
