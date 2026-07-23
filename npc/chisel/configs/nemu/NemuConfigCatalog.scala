package scpu

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** 为 Make 的 `host-config-list` 写出显式登记的 NEMU Base。 */
object DescribeNemuConfigCatalog extends App {
  require(args.length == 1, "用法：scpu.DescribeNemuConfigCatalog <output.tsv>")
  val output = Path.of(args(0)).toAbsolutePath.normalize
  val rows = NemuHostConfig.registeredPresets.map { preset =>
    val settings = preset.config
    val bit = (value: Boolean) => if (value) 1 else 0
    val policy = Seq(
      s"trace=${bit(settings.trace)}",
      s"watchpoint=${bit(settings.watchpoint)}",
      s"vcd=${bit(settings.vcd)}",
      s"performance-html=${bit(settings.performanceHtml)}",
      s"pipeline-html=${bit(settings.pipelineHtml)}",
      s"software-difftest=${bit(settings.softwareDifftest)}",
      s"devices=${bit(settings.devices)}",
      s"opt=${settings.optimization}",
      s"debug=${bit(settings.debug)}",
      s"lto=${bit(settings.lto)}",
      s"asan=${bit(settings.asan)}"
    ).mkString(",")
    s"${preset.name}\t${settings.backend.id}\t$policy"
  }
  Option(output.getParent).foreach(Files.createDirectories(_))
  val content = ("# 此文件由 scpu.DescribeNemuConfigCatalog 自动生成；不要手工编辑。" +: rows)
    .mkString("\n") + "\n"
  Files.writeString(output, content, StandardCharsets.UTF_8)
}
