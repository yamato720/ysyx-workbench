package scpu

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

/** 从完整 Scala Config 的源码生成 Make 使用的构造目录。
  *
  * TSV 只是 Make 在启动 JVM 前需要的快照，不是另一份人工维护的配置源。可选择
  * 构造由现有层级和命名约定识别：L1 的非检查 `Npc...Config`、带 standalone 或
  * simulation 平台的 ysyxSoC Config，以及 `fpga/<board>/` 下的 FPGA 终端 Config。
  */
object ConfigCatalogGenerator {
  private val CatalogRelativePath = Path.of("chisel", "configs", "resources", "scpu-config-catalog.tsv")
  private val ConfigRelativePath = Path.of("chisel", "configs")
  private val PackagePattern: Regex = raw"(?m)^package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$$".r
  private val ClassPattern: Regex = raw"(?m)^\s*class\s+([A-Za-z_][A-Za-z0-9_]*Config)\s+extends\s+([A-Za-z_][A-Za-z0-9_.]*)".r
  private val BoardPattern: Regex = raw"WithFpgaBoardConfig\(FpgaBoard\.([A-Za-z0-9_]+)\)".r

  private final case class ClassBlock(name: String, parent: String, body: String)

  /** 寻找包含 `chisel/configs` 的 NPC 根目录；无法找到时返回 `None`，供安装后的
    * classpath resource 回退路径使用。
    */
  def locateNpcRoot(start: Path = Paths.get("").toAbsolutePath.normalize): Option[Path] = {
    def isNpcRoot(path: Path): Boolean = Files.isDirectory(path.resolve(ConfigRelativePath))

    Iterator.iterate(Option(start))(_.flatMap(path => Option(path.getParent))).flatten
      .flatMap(path => Seq(path, path.resolve("npc")))
      .find(isNpcRoot)
  }

  def catalogPath(npcRoot: Path): Path = npcRoot.resolve(CatalogRelativePath)

  private def read(path: Path): String = Files.readString(path, StandardCharsets.UTF_8)

  private def scalaFiles(directory: Path): Vector[Path] = {
    if (!Files.isDirectory(directory)) Vector.empty
    else {
      val stream = Files.walk(directory)
      try stream.iterator.asScala.filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala")).toVector
      finally stream.close()
    }
  }

  private def classBlocks(source: String): Vector[ClassBlock] = {
    val matches = ClassPattern.findAllMatchIn(source).toVector
    matches.zipWithIndex.map { case (matched, index) =>
      val end = matches.lift(index + 1).map(_.start).getOrElse(source.length)
      ClassBlock(matched.group(1), matched.group(2), source.substring(matched.start, end))
    }
  }

  private def packageName(source: String, path: Path): String =
    PackagePattern.findFirstMatchIn(source).map(_.group(1)).getOrElse(
      throw new IllegalArgumentException(s"Missing package declaration in $path")
    )

  private def discoverNpc(configRoot: Path): Vector[ConfigCatalog.Entry] =
    scalaFiles(configRoot.resolve("npc")).flatMap { path =>
      val source = read(path)
      val pkg = packageName(source, path)
      classBlocks(source).collect {
        case block if block.parent == "NpcConstructionConfig" && !block.name.contains("Check") =>
          ConfigCatalog.Entry(
            shortName = block.name,
            className = s"$pkg.${block.name}",
            scope = "npc",
            board = None,
            target = "NPC"
          )
      }
    }

  private def discoverSoc(configRoot: Path): Vector[ConfigCatalog.Entry] =
    scalaFiles(configRoot.resolve("ysyx")).flatMap { path =>
      val source = read(path)
      val pkg = packageName(source, path)
      classBlocks(source).collect {
        case block
            if block.parent == "CDEConfig" &&
              (block.body.contains("WithYsyxPlatformConfig(YsyxPlatform.Standalone)") ||
                block.body.contains("WithYsyxPlatformConfig(YsyxPlatform.Simulation)")) =>
          ConfigCatalog.Entry(
            shortName = block.name,
            className = s"$pkg.${block.name}",
            scope = "soc",
            board = None,
            target = "SOC"
          )
      }
    }

  private def boardMetadata(directory: Path): String = {
    val source = scalaFiles(directory).map(read).mkString("\n")
    val board = BoardPattern.findFirstMatchIn(source).map(_.group(1)).map(_.toLowerCase).getOrElse(
      throw new IllegalArgumentException(s"Missing WithFpgaBoardConfig in $directory")
    )
    directory.getFileName.toString match {
      case name if name == board => board
      case name => throw new IllegalArgumentException(
        s"FPGA directory $name conflicts with its FpgaBoard.$board declaration"
      )
    }
  }

  private def discoverFpga(configRoot: Path): Vector[ConfigCatalog.Entry] = {
    val fpgaRoot = configRoot.resolve("fpga")
    if (!Files.isDirectory(fpgaRoot)) Vector.empty
    else {
      val directories = Files.list(fpgaRoot)
      try directories.iterator.asScala.filter(Files.isDirectory(_)).toVector.flatMap { directory =>
        if (directory.getFileName.toString == "common") Vector.empty
        else {
          val board = boardMetadata(directory)
          scalaFiles(directory).flatMap { path =>
            val source = read(path)
            val pkg = packageName(source, path)
            classBlocks(source).collect {
              case block if block.parent == "CDEConfig" && block.name.endsWith("NpcFpgaConfig") =>
                ConfigCatalog.Entry(
                  shortName = block.name,
                  className = s"$pkg.${block.name}",
                  scope = "fpga-npc",
                  board = Some(board),
                  target = "NPC"
                )
              case block if block.parent == "CDEConfig" && block.name.endsWith("YsyxSocFpgaConfig") =>
                ConfigCatalog.Entry(
                  shortName = block.name,
                  className = s"$pkg.${block.name}",
                  scope = "fpga-soc",
                  board = Some(board),
                  target = "SOC"
                )
            }
          }
        }
      }
      finally directories.close()
    }
  }

  private def validate(entries: Vector[ConfigCatalog.Entry]): Vector[ConfigCatalog.Entry] = {
    val duplicateShortNames = entries.groupBy(_.shortName).collect { case (name, values) if values.size > 1 => name }
    val duplicateClassNames = entries.groupBy(_.className).collect { case (name, values) if values.size > 1 => name }
    require(duplicateShortNames.isEmpty, s"Duplicate generated Config short names: ${duplicateShortNames.toSeq.sorted.mkString(", ")}")
    require(duplicateClassNames.isEmpty, s"Duplicate generated Config class names: ${duplicateClassNames.toSeq.sorted.mkString(", ")}")
    require(entries.nonEmpty, s"No Make-selectable Configs found below $ConfigRelativePath")

    val scopeOrder = Map("npc" -> 0, "soc" -> 1, "fpga-npc" -> 2, "fpga-soc" -> 3)
    entries.sortBy(entry => (scopeOrder.getOrElse(entry.scope, Int.MaxValue), entry.shortName))
  }

  /** 扫描当前源码树；供测试和运行时目录加载共用。 */
  def discover(npcRoot: Path): Vector[ConfigCatalog.Entry] = {
    val configRoot = npcRoot.resolve(ConfigRelativePath)
    require(Files.isDirectory(configRoot), s"Missing Scala Config root $configRoot")
    validate(discoverNpc(configRoot) ++ discoverSoc(configRoot) ++ discoverFpga(configRoot))
  }

  private def render(entries: Vector[ConfigCatalog.Entry]): String = {
    val rows = entries.map { entry =>
      val board = entry.board.getOrElse("-")
      s"${entry.shortName}\t${entry.className}\t${entry.scope}\t$board\t${entry.target}"
    }
    (Vector(
      "# 此文件由 scpu.GenerateConfigCatalog 自动生成；不要手工编辑。",
      "# 短名\t完整类名\t作用域\t板卡\t目标"
    ) ++ rows).mkString("\n") + "\n"
  }

  /** 仅在内容变化时更新 Make 快照，避免无意义地触发资源重编译。 */
  def writeIfChanged(npcRoot: Path): Path = {
    val destination = catalogPath(npcRoot)
    val content = render(discover(npcRoot))
    val previous = Option.when(Files.isRegularFile(destination))(read(destination))
    if (!previous.contains(content)) {
      Files.createDirectories(destination.getParent)
      val temporary = Files.createTempFile(destination.getParent, ".scpu-config-catalog-", ".tsv")
      try {
        Files.writeString(temporary, content, StandardCharsets.UTF_8)
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
      } finally Files.deleteIfExists(temporary)
    }
    destination
  }

  /** 本地源码树存在时更新并返回目录位置；发布 JAR 则回退到 classpath resource。 */
  def ensureCurrent(): Option[Path] = locateNpcRoot().map(writeIfChanged)
}

/** 供 Make 启动的生成入口。 */
object GenerateConfigCatalog extends App {
  val root = args.headOption.map(Paths.get(_).toAbsolutePath.normalize)
    .orElse(ConfigCatalogGenerator.locateNpcRoot())
    .getOrElse(throw new IllegalArgumentException("Cannot locate NPC source root for Config catalog generation"))
  val catalog = ConfigCatalogGenerator.writeIfChanged(root)
  println(catalog)
}
