package npc

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

/** 从完整 Scala Config 的源码生成 Make 使用的构造目录。
  *
  * TSV 只是 Make 在启动 JVM 前需要的快照，不是另一份人工维护的配置源。可选择
  * 构造必须混入显式 terminal 层 trait；目录不再从 `SimulationConfig`、`FpgaConfig` 等类名
  * 后缀猜测作用域或目标。
  */
object ConfigCatalogGenerator {
  private val CatalogRelativePath = Path.of("chisel", "configs", "resources", "npc-config-catalog.tsv")
  private val ConfigRelativePath = Path.of("chisel", "configs")
  private val FpgaRelativePath = Path.of("fpga")
  private val FpgaConfigRelativePath = ConfigRelativePath.resolve("fpga")
  private val PackagePattern: Regex = raw"(?m)^package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$$".r
  private val ClassPattern: Regex = raw"(?m)^\s*class\s+([A-Za-z_][A-Za-z0-9_]*Config)\s+extends\s+([A-Za-z_][A-Za-z0-9_.]*)".r
  private val BoardPattern: Regex = raw"WithFpgaBoardConfig\(FpgaBoard\.([A-Za-z0-9_]+)\)".r

  private final case class ClassBlock(name: String, parent: String, body: String)

  private val terminalTraits = Map(
    "LocalNpcTerminal" -> ("npc", "NPC"),
    "LocalSocTerminal" -> ("soc", "SOC"),
    "LocalSpmvSimulationTerminal" -> ("spmv", "SPMV"),
    "LocalSpmvPerformanceMonitorTerminal" -> ("spmv", "SPMV"),
    "U55cNpcTerminal" -> ("fpga", "NPC"),
    "U55cNpcPerformanceMonitorTerminal" -> ("fpga", "NPC"),
    "U55cSpmvSynthesisTerminal" -> ("fpga", "SPMV"),
    "U55cSpmvBitstreamTerminal" -> ("fpga", "SPMV"),
    "U55cSocTerminal" -> ("fpga", "SOC"),
    "Zcu102NpcTerminal" -> ("fpga", "NPC"),
    "Zcu102SocTerminal" -> ("fpga", "SOC")
  )
  private val ipTerminalForTarget = Map(
    "NPC" -> "FpgaIpTerminal",
    "SOC" -> "FpgaIpTerminal"
  )
  private val ipTerminalNames = Vector("NemuSimulationIpTerminal", "FpgaIpTerminal")
  private val baseConstructionTraits = Vector(
    "HostConstruction",
    "AcceleratorHostConstruction",
    "FpgaToolchainConstruction",
    "NemuSimulationConstruction",
    "FpgaConstruction",
    "FpgaSynthesisConstruction",
    "FpgaBitstreamConstruction",
    "MakeTerminal"
  )
  /** 寻找同时包含通用 Config 与 FPGA 板卡 Config 的 NPC 根目录；无法找到时返回 `None`，供安装后的
    * classpath resource 回退路径使用。
    */
  def locateNpcRoot(start: Path = Paths.get("").toAbsolutePath.normalize): Option[Path] = {
    def isNpcRoot(path: Path): Boolean =
      Files.isDirectory(path.resolve(ConfigRelativePath)) && Files.isDirectory(path.resolve(FpgaRelativePath))

    Iterator.iterate(Option(start))(_.flatMap(path => Option(path.getParent))).flatten
      .flatMap(path => Seq(path, path.resolve("npc")))
      .find(isNpcRoot)
  }

  def catalogPath(npcRoot: Path): Path = npcRoot.resolve(CatalogRelativePath)

  /** 将注释和字面量掩码为空白，保留行列位置供后续正则按源码结构发现类。
    *
    * 自动目录不能把文档中的示例类或注释掉的历史 Config 视为真实构造；同时保留换行，避免
    * 多行类声明的匹配位置发生偏移。
    */
  private[npc] def codeOnly(source: String): String = {
    val masked = source.toCharArray

    def mask(index: Int): Unit =
      if (masked(index) != '\n' && masked(index) != '\r') masked(index) = ' '

    def maskRange(start: Int, end: Int): Unit =
      (start until end).foreach(mask)

    var index = 0
    while (index < source.length) {
      if (source.startsWith("//", index)) {
        val end = source.indexOf('\n', index) match {
          case -1 => source.length
          case value => value
        }
        maskRange(index, end)
        index = end
      } else if (source.startsWith("/*", index)) {
        var depth = 1
        maskRange(index, index + 2)
        index += 2
        while (index < source.length && depth > 0) {
          if (source.startsWith("/*", index)) {
            depth += 1
            maskRange(index, index + 2)
            index += 2
          } else if (source.startsWith("*/", index)) {
            depth -= 1
            maskRange(index, index + 2)
            index += 2
          } else {
            mask(index)
            index += 1
          }
        }
      } else if (source.startsWith("\"\"\"", index)) {
        val end = source.indexOf("\"\"\"", index + 3) match {
          case -1 => source.length
          case value => value + 3
        }
        maskRange(index, end)
        index = end
      } else if (source(index) == '"' || source(index) == '\'') {
        val quote = source(index)
        mask(index)
        index += 1
        var closed = false
        while (index < source.length && !closed) {
          if (source(index) == '\\' && index + 1 < source.length) {
            mask(index)
            mask(index + 1)
            index += 2
          } else {
            closed = source(index) == quote
            mask(index)
            index += 1
          }
        }
      } else {
        index += 1
      }
    }
    new String(masked)
  }

  private def read(path: Path): String = codeOnly(Files.readString(path, StandardCharsets.UTF_8))

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

  /** 每个可运行领域只允许根部 `Configs.scala` 定义终端。
    *
    * `base/` 与 `core/` 仍由 Scala 编译器递归加载，但终端 trait 一旦出现在这些层或其他文件中，
    * 目录生成立即失败，避免可复用组合被意外暴露为 Make 入口。
    */
  private[npc] def validateTerminalLayout(directory: Path): Path = {
    val terminalPath = directory.resolve("Configs.scala").toAbsolutePath.normalize
    require(Files.isRegularFile(terminalPath), s"终端目录 $directory 缺少根部 Configs.scala")

    val misplaced = scalaFiles(directory).flatMap { path =>
      val normalized = path.toAbsolutePath.normalize
      if (normalized == terminalPath) Vector.empty
      else {
        val source = read(path)
        classBlocks(source).flatMap { block =>
          terminalMetadata(block).map(_ => s"${directory.relativize(path)}:${block.name}")
        }
      }
    }
    require(misplaced.isEmpty,
      s"terminal 层 trait 只能挂载在 $terminalPath，发现：${misplaced.sorted.mkString(", ")}")

    val terminalSource = read(terminalPath)
    val terminalBlocks = classBlocks(terminalSource)
    val unmarked = terminalBlocks.filter(block => terminalMetadata(block).isEmpty).map(_.name)
    require(unmarked.isEmpty,
      s"$terminalPath 只能包含挂载 terminal 层 trait 的 Config，发现：${unmarked.sorted.mkString(", ")}")
    val directBaseTraits = terminalBlocks.flatMap { block =>
      baseConstructionTraits.collect {
        case name if raw"\b$name\b".r.findFirstIn(block.body).nonEmpty => s"${block.name}:$name"
      }
    }
    require(directBaseTraits.isEmpty,
      s"$terminalPath 的终端只能直接挂载一个 terminal 层 trait，不能混入 base trait：" +
        directBaseTraits.sorted.mkString(", "))
    terminalPath
  }

  private def packageName(source: String, path: Path): String =
    PackagePattern.findFirstMatchIn(source).map(_.group(1)).getOrElse(
      throw new IllegalArgumentException(s"Missing package declaration in $path")
    )

  private def terminalMetadata(block: ClassBlock): Option[(String, String)] = {
    val found = terminalTraits.collect {
      case (name, metadata) if raw"\b$name\b".r.findFirstIn(block.body).nonEmpty => name -> metadata
    }.toVector
    require(found.size <= 1, s"Config ${block.name} 挂载了多个 terminal 层 trait")
    found.headOption.map(_._2)
  }

  /** 运行终端必须在根部 Config 中直接写出唯一的计算 IP terminal。
    *
    * 这与 NEMU/FPGA host 配方同样是终端 ABI 的一部分；核心和 CDE `++` 链不能
    * 隐式选择或重复挂载它。
    */
  private def validateIpTerminal(block: ClassBlock, scope: String, target: String): Unit = {
    val mounted = ipTerminalNames.flatMap { name =>
      val occurrences = raw"\b$name\b".r.findAllMatchIn(block.body).size
      Option.when(occurrences > 0)(name -> occurrences)
    }
    if (target == "SPMV") {
      require(mounted.isEmpty,
        s"Config ${block.name} 的 SPMV 终端不能挂载 CPU 计算 IP terminal，实际为 " +
          mounted.map { case (name, count) => s"$name x$count" }.mkString(", "))
    } else {
      val expected = if (scope == "npc" || scope == "soc") "NemuSimulationIpTerminal"
        else ipTerminalForTarget(target)
      require(mounted == Vector(expected -> 1),
        s"Config ${block.name} 必须直接且唯一地挂载 $expected，实际为 " +
          mounted.map { case (name, count) => s"$name x$count" }.mkString(", "))
    }
    val nested = ipTerminalNames.filter { name =>
      raw"(?s)\bnew\s+[^()]*?\bwith\s+$name\b".r.findFirstIn(block.body).nonEmpty
    }
    require(nested.isEmpty,
      s"Config ${block.name} 只能在公开终端自身挂载计算 IP，不能在 CDE ++ 链中挂载：" +
        nested.mkString(", "))
  }

  private def discoverTerminals(
    directory: Path,
    expectedScope: String,
    board: Option[String]
  ): Vector[ConfigCatalog.Entry] = {
    val path = validateTerminalLayout(directory)
    val source = read(path)
    val pkg = packageName(source, path)
    classBlocks(source).map { block =>
      val (scope, target) = terminalMetadata(block).getOrElse(
        throw new IllegalArgumentException(s"终端文件 $path 中的 ${block.name} 缺少 terminal 层 trait")
      )
      require(scope == expectedScope,
        s"Config ${block.name} 的终端 trait 作用域 $scope 与目录 $directory 不一致")
      validateIpTerminal(block, scope, target)
      val expectedParent = if (scope == "npc") "ConstructionConfig" else "CDEConfig"
      require(block.parent == expectedParent,
        s"Config ${block.name} 的终端 trait 要求继承 $expectedParent，实际为 ${block.parent}")
      ConfigCatalog.Entry(
        shortName = block.name,
        className = s"$pkg.${block.name}",
        scope = scope,
        board = board,
        target = target
      )
    }
  }

  private def discoverNpc(configRoot: Path): Vector[ConfigCatalog.Entry] =
    discoverTerminals(configRoot.resolve("npc"), "npc", None)

  private def discoverSoc(configRoot: Path): Vector[ConfigCatalog.Entry] =
    discoverTerminals(configRoot.resolve("ysyx"), "soc", None)

  private def discoverSpmv(configRoot: Path): Vector[ConfigCatalog.Entry] =
    discoverTerminals(configRoot.resolve("spmv"), "spmv", None)

  private def boardMetadata(boardDirectory: Path, configDirectory: Path): String = {
    val source = scalaFiles(configDirectory).map(read).mkString("\n")
    val board = BoardPattern.findFirstMatchIn(source).map(_.group(1)).map(_.toLowerCase).getOrElse(
      throw new IllegalArgumentException(s"Missing WithFpgaBoardConfig in $configDirectory")
    )
    boardDirectory.getFileName.toString match {
      case name if name == board => board
      case name => throw new IllegalArgumentException(
        s"FPGA directory $name conflicts with its FpgaBoard.$board declaration"
      )
    }
  }

  private def discoverFpga(npcRoot: Path): Vector[ConfigCatalog.Entry] = {
    val fpgaConfigRoot = npcRoot.resolve(FpgaConfigRelativePath)
    if (!Files.isDirectory(fpgaConfigRoot)) Vector.empty
    else {
      val directories = Files.list(fpgaConfigRoot)
      try directories.iterator.asScala.filter(Files.isDirectory(_)).toVector.flatMap { boardDirectory =>
        if (boardDirectory.getFileName.toString == "common") Vector.empty
        else {
          val board = boardMetadata(boardDirectory, boardDirectory)
          discoverTerminals(boardDirectory, "fpga", Some(board))
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

    val scopeOrder = Map("npc" -> 0, "soc" -> 1, "spmv" -> 2, "fpga" -> 3)
    entries.sortBy(entry => (scopeOrder.getOrElse(entry.scope, Int.MaxValue), entry.shortName))
  }

  /** 扫描当前源码树；供测试和运行时目录加载共用。 */
  def discover(npcRoot: Path): Vector[ConfigCatalog.Entry] = {
    val configRoot = npcRoot.resolve(ConfigRelativePath)
    require(Files.isDirectory(configRoot), s"Missing Scala Config root $configRoot")
    validate(discoverNpc(configRoot) ++ discoverSoc(configRoot) ++ discoverSpmv(configRoot) ++ discoverFpga(npcRoot))
  }

  private def render(entries: Vector[ConfigCatalog.Entry]): String = {
    val rows = entries.map { entry =>
      val board = entry.board.getOrElse("-")
      s"${entry.shortName}\t${entry.className}\t${entry.scope}\t$board\t${entry.target}"
    }
    (Vector(
      "# 此文件由 npc.GenerateConfigCatalog 自动生成；不要手工编辑。",
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
      val temporary = Files.createTempFile(destination.getParent, ".npc-config-catalog-", ".tsv")
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
