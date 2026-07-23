package scpu

/** 仅供 Scala/RTL 检查构造直接挂载；它不是 Make 构造或运行入口。 */
trait CheckOnlyConstruction {
  final val capability: String = "check-only"
}
