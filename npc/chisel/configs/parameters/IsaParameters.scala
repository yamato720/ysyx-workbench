package scpu

/** 所有生成目标共享的 ISA 选择。 */
case class ISAConfig(
  xlen: Int = 64,
  M: Boolean = false,
  F: Boolean = false,
  Zicsr: Boolean = true
) {
  require(!F || Zicsr, "RISC-V F requires Zicsr for FCSR and FS state")
}

/** 乘除法和浮点算术单元的实现与时序参数。 */
case class OperatorConfig(
  mulDiv: MulDivAlu.Config = MulDivAlu.Config(),
  floating: FloatingAlu.Config = FloatingAlu.Config(),
  routes: OperatorRouteConfig = OperatorRouteConfig()
)
