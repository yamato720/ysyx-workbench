package scpu

import java.nio.file.Files
import scala.io.Source
import scala.util.Using

/** Make 和 Scala 共同使用的可选择构造目录。
  *
  * TSV 是由 [[ConfigCatalogGenerator]] 从完整、无参且可由 Make 路由的构造自动
  * 生成的快照。局部 `With...Config` 仍只用于 Scala 源码中的组合，避免 Make 在
  * 启动 JVM 前猜测其板卡或目标。
  */
object ConfigCatalog {
  final case class Entry(
    shortName: String,
    className: String,
    scope: String,
    board: Option[String],
    target: String
  )

  private val resourceName = "/scpu-config-catalog.tsv"

  private def load(): Vector[Entry] = {
    val stream = ConfigCatalogGenerator.ensureCurrent().map(path => Files.newInputStream(path))
      .orElse(Option(getClass.getResourceAsStream(resourceName))).getOrElse(
      throw new IllegalStateException(s"Missing configuration catalog resource $resourceName")
    )
    val entries = Using.resource(Source.fromInputStream(stream, "UTF-8")) { source =>
      source.getLines().zipWithIndex.collect {
        case (line, _) if line.trim.isEmpty || line.startsWith("#") => None
        case (line, index) =>
          line.split("\\t", -1).toList match {
            case shortName :: className :: scope :: board :: target :: Nil =>
              Some(Entry(shortName, className, scope, Option(board).filterNot(_ == "-"), target))
            case _ => throw new IllegalArgumentException(s"Invalid catalog row ${index + 1} in $resourceName")
          }
      }.flatten.toVector
    }
    val aliases = entries.groupBy(_.shortName).collect { case (name, values) if values.size > 1 => name }
    val classes = entries.groupBy(_.className).collect { case (name, values) if values.size > 1 => name }
    require(aliases.isEmpty, s"Duplicate Config short names: ${aliases.toSeq.sorted.mkString(", ")}")
    require(classes.isEmpty, s"Duplicate Config class names: ${classes.toSeq.sorted.mkString(", ")}")
    entries
  }

  lazy val entries: Vector[Entry] = load()

  /** 返回 Make/SBT/Mill 传入的构造名。
    *
    * Mill 的 `runMain` 在独立 JVM 中执行，启动 Mill 时的 `-Dnpc.config`
    * 不会自动转发给该 JVM。因此 Make 同时设置 `NPC_SCALA_CONFIG`。保留
    * system property 是为了 SBT 和直接 Scala 调用；两者同时存在时必须一致，
    * 防止构造记录与实际 elaboration 脱节。
    */
  def selectedName(defaultName: String): String = {
    def nonEmpty(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)

    val systemValue = nonEmpty(sys.props.get("npc.config"))
    val environmentValue = nonEmpty(sys.env.get("NPC_SCALA_CONFIG"))
    (systemValue, environmentValue) match {
      case (Some(system), Some(environment)) =>
        require(system == environment,
          s"npc.config=$system conflicts with NPC_SCALA_CONFIG=$environment")
        system
      case (Some(system), None) => system
      case (None, Some(environment)) => environment
      case (None, None) => defaultName
    }
  }

  def resolve(request: String, allowedScopes: Set[String]): Entry = {
    val normalized = request.trim
    require(normalized.nonEmpty, "Configuration name must not be empty")
    val entry = entries.find(candidate => candidate.shortName == normalized || candidate.className == normalized).getOrElse(
      throw new IllegalArgumentException(
        s"Unknown Make configuration '$normalized'; add a discoverable complete Config below chisel/configs"
      )
    )
    require(allowedScopes.contains(entry.scope),
      s"Configuration ${entry.className} has scope '${entry.scope}', expected one of ${allowedScopes.toSeq.sorted.mkString(", ")}")
    entry
  }
}
