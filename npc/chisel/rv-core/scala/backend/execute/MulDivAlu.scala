package npc

import chisel3._
import chisel3.util._
import npc.ip.arithmetic._

/** RV32M/RV64M 执行外壳。它译码架构 ALU 操作，发射可复用乘除法算子并返回响应。
  * 算子本身位于 `compute/`，可供其他模块复用。
  */
object MulDivAlu {
  case class Config(
    implementation: ComputeUnitConfig = ComputeUnitConfig(),
    completionCycles: Int = 8,
    multiplyTiming: ArithmeticIpTiming = ArithmeticIpTiming(latency = 3),
    dividerInitiationInterval: Int = 1,
    tagWidth: Int = 4,
    multiplyAdapterModuleName: String = "npc_int_multiplier_adapter",
    dividerAdapterModuleName: String = "npc_int_divider_adapter",
    dividerAdapterNonBlocking: Boolean = false,
  ) {
    require(completionCycles >= 1, s"MulDivAlu completionCycles must be positive, got $completionCycles")
    require(tagWidth >= 1, s"MulDivAlu tagWidth must be positive, got $tagWidth")
    require(multiplyAdapterModuleName.nonEmpty, "Integer multiplier adapter module name must not be empty")
    require(dividerAdapterModuleName.nonEmpty, "Integer divider adapter module name must not be empty")

    def divideTiming: ArithmeticIpTiming = ArithmeticIpTiming(
      latency = completionCycles,
      initiationInterval = dividerInitiationInterval,
      responseFifoDepth = implementation.ip.outputFifoDepth
    )
  }

  def isMultiply(op: UInt): Bool =
    op === NpcAluOp.MulDiv.MUL.asUInt || op === NpcAluOp.MulDiv.MULH.asUInt || op === NpcAluOp.MulDiv.MULHSU.asUInt ||
      op === NpcAluOp.MulDiv.MULHU.asUInt || op === NpcAluOp.MulDiv.MULW.asUInt

  def isDivide(op: UInt): Bool =
    op === NpcAluOp.MulDiv.DIV.asUInt || op === NpcAluOp.MulDiv.DIVU.asUInt || op === NpcAluOp.MulDiv.REM.asUInt ||
      op === NpcAluOp.MulDiv.REMU.asUInt || op === NpcAluOp.MulDiv.DIVW.asUInt || op === NpcAluOp.MulDiv.DIVUW.asUInt ||
      op === NpcAluOp.MulDiv.REMW.asUInt || op === NpcAluOp.MulDiv.REMUW.asUInt
}

class MulDivAlu(
  width: Int,
  config: MulDivAlu.Config = MulDivAlu.Config(),
  routes: OperatorRouteConfig = OperatorRouteConfig(),
  provider: ArithmeticIpProvider = SimulationIpComponents
) extends Module {
  require(width == 32 || width == 64, s"MulDivAlu supports RV32/RV64, got width=$width")
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new AluRequest(width, config.tagWidth)))
    val resp = Decoupled(new ArithmeticResponse(width, config.tagWidth))
  })

  private val routeEnabled = routes.routes.nonEmpty
  private val multiplyRoutes = Seq(
    ArithmeticRouteOperation.Mul -> NpcAluOp.MulDiv.MUL,
    ArithmeticRouteOperation.Mulh -> NpcAluOp.MulDiv.MULH,
    ArithmeticRouteOperation.Mulhsu -> NpcAluOp.MulDiv.MULHSU,
    ArithmeticRouteOperation.Mulhu -> NpcAluOp.MulDiv.MULHU,
    ArithmeticRouteOperation.Mulw -> NpcAluOp.MulDiv.MULW
  )
  private val divideRoutes = Seq(
    ArithmeticRouteOperation.Div -> NpcAluOp.MulDiv.DIV,
    ArithmeticRouteOperation.Divu -> NpcAluOp.MulDiv.DIVU,
    ArithmeticRouteOperation.Rem -> NpcAluOp.MulDiv.REM,
    ArithmeticRouteOperation.Remu -> NpcAluOp.MulDiv.REMU,
    ArithmeticRouteOperation.Divw -> NpcAluOp.MulDiv.DIVW,
    ArithmeticRouteOperation.Divuw -> NpcAluOp.MulDiv.DIVUW,
    ArithmeticRouteOperation.Remw -> NpcAluOp.MulDiv.REMW,
    ArithmeticRouteOperation.Remuw -> NpcAluOp.MulDiv.REMUW
  )
  private def selectedFor(
    candidates: Seq[(ArithmeticRouteOperation, NpcAluOp.MulDiv.Type)],
    targets: Set[OperatorRouteTarget]
  ): Bool = {
    val selected = candidates.collect {
      case (operation, aluOperation) if targets.contains(routes.route(operation).target) =>
        io.req.bits.aluOp === aluOperation.asUInt
    }
    selected.reduceOption(_ || _).getOrElse(false.B)
  }
  private def hasRoute(
    candidates: Seq[(ArithmeticRouteOperation, NpcAluOp.MulDiv.Type)],
    targets: Set[OperatorRouteTarget]
  ): Boolean = candidates.exists { case (operation, _) => targets.contains(routes.route(operation).target) }
  private def firstRoute(
    candidates: Seq[(ArithmeticRouteOperation, NpcAluOp.MulDiv.Type)],
    targets: Set[OperatorRouteTarget],
    fallback: String
  ): OperatorRoute = candidates.collectFirst {
    case (operation, _) if targets.contains(routes.route(operation).target) => routes.route(operation)
  }.getOrElse(OperatorRoute(OperatorRouteTarget.Model, fallback, width, 1, 1))

  private val vendorTargets: Set[OperatorRouteTarget] = Set(OperatorRouteTarget.VendorIp)
  private val directTargets: Set[OperatorRouteTarget] = Set(OperatorRouteTarget.Model, OperatorRouteTarget.DirectLogic)
  private val vendorMultiplySelected = if (routeEnabled) selectedFor(multiplyRoutes, vendorTargets) else MulDivAlu.isMultiply(io.req.bits.aluOp)
  private val vendorDivideSelected = if (routeEnabled) selectedFor(divideRoutes, vendorTargets) else MulDivAlu.isDivide(io.req.bits.aluOp)
  private val directMultiplySelected = if (routeEnabled) selectedFor(multiplyRoutes, directTargets) else false.B
  private val directDivideSelected = if (routeEnabled) selectedFor(divideRoutes, directTargets) else false.B

  private def legacySpec(moduleName: String): ArithmeticEndpointSpec = config.implementation.backend match {
    case ComputeBackend.IP | ComputeBackend.FPGA =>
      ArithmeticEndpointSpec(ArithmeticEndpointImplementation.External, moduleName)
    case _ => ArithmeticEndpointSpec(ArithmeticEndpointImplementation.IntegerReference)
  }
  private val vendorMultiplier = if (!routeEnabled || hasRoute(multiplyRoutes, vendorTargets)) Some(
    provider.makeIntegerMultiplier(width, config.tagWidth, config.multiplyTiming,
      if (routeEnabled) ArithmeticEndpointSpec(ArithmeticEndpointImplementation.External,
        firstRoute(multiplyRoutes, vendorTargets, config.multiplyAdapterModuleName).moduleName)
      else legacySpec(config.multiplyAdapterModuleName))) else None
  private val vendorDividerSpec = {
    val base = if (routeEnabled) ArithmeticEndpointSpec(ArithmeticEndpointImplementation.External,
      firstRoute(divideRoutes, vendorTargets, config.dividerAdapterModuleName).moduleName)
    else legacySpec(config.dividerAdapterModuleName)
    base.copy(adapterParameters = base.adapterParameters.updated("NON_BLOCKING",
      if (config.dividerAdapterNonBlocking) 1 else 0))
  }
  private val vendorDivider = if (!routeEnabled || hasRoute(divideRoutes, vendorTargets)) Some(
    provider.makeIntegerDivider(width, config.tagWidth, config.divideTiming,
      vendorDividerSpec)) else None
  private val directMultiplier = if (routeEnabled && hasRoute(multiplyRoutes, directTargets)) Some(
    provider.makeIntegerMultiplier(width, config.tagWidth, config.multiplyTiming,
      ArithmeticEndpointSpec(ArithmeticEndpointImplementation.IntegerReference))) else None
  private val directDivider = if (routeEnabled && hasRoute(divideRoutes, directTargets)) Some(
    provider.makeIntegerDivider(width, config.tagWidth, config.divideTiming,
      ArithmeticEndpointSpec(ArithmeticEndpointImplementation.IntegerReference))) else None
  private val multiplySelected = MulDivAlu.isMultiply(io.req.bits.aluOp)
  private val divideSelected = MulDivAlu.isDivide(io.req.bits.aluOp)
  private val multiplyOperation = MuxLookup(io.req.bits.aluOp, 0.U(ArithmeticOperation.width.W))(Seq(
    NpcAluOp.MulDiv.MUL.asUInt -> IntegerMultiplyOperation.low.asUInt,
    NpcAluOp.MulDiv.MULH.asUInt -> IntegerMultiplyOperation.signedHigh.asUInt,
    NpcAluOp.MulDiv.MULHSU.asUInt -> IntegerMultiplyOperation.signedUnsignedHigh.asUInt,
    NpcAluOp.MulDiv.MULHU.asUInt -> IntegerMultiplyOperation.unsignedHigh.asUInt,
    NpcAluOp.MulDiv.MULW.asUInt -> IntegerMultiplyOperation.wordLow.asUInt
  ))
  private val divideOperation = MuxLookup(io.req.bits.aluOp, 0.U(ArithmeticOperation.width.W))(Seq(
    NpcAluOp.MulDiv.DIV.asUInt -> IntegerDivideOperation.signedQuotient.asUInt,
    NpcAluOp.MulDiv.DIVU.asUInt -> IntegerDivideOperation.unsignedQuotient.asUInt,
    NpcAluOp.MulDiv.REM.asUInt -> IntegerDivideOperation.signedRemainder.asUInt,
    NpcAluOp.MulDiv.REMU.asUInt -> IntegerDivideOperation.unsignedRemainder.asUInt,
    NpcAluOp.MulDiv.DIVW.asUInt -> IntegerDivideOperation.signedWordQuotient.asUInt,
    NpcAluOp.MulDiv.DIVUW.asUInt -> IntegerDivideOperation.unsignedWordQuotient.asUInt,
    NpcAluOp.MulDiv.REMW.asUInt -> IntegerDivideOperation.signedWordRemainder.asUInt,
    NpcAluOp.MulDiv.REMUW.asUInt -> IntegerDivideOperation.unsignedWordRemainder.asUInt
  ))

  private def forwardRequest(endpoint: ArithmeticOperatorIO, selected: Bool, operation: UInt): Unit = {
    endpoint.req.valid := io.req.valid && selected
    endpoint.req.bits.operandA := io.req.bits.operandA
    endpoint.req.bits.operandB := io.req.bits.operandB
    endpoint.req.bits.operandC := io.req.bits.operandC
    endpoint.req.bits.operation := operation
    endpoint.req.bits.roundingMode := io.req.bits.roundingMode
    endpoint.req.bits.pc := io.req.bits.pc
    endpoint.req.bits.instruction := io.req.bits.instruction
    endpoint.req.bits.fcsr := io.req.bits.fcsr
    endpoint.req.bits.tag := io.req.bits.tag
  }
  vendorMultiplier.foreach(endpoint => forwardRequest(endpoint.io, vendorMultiplySelected, multiplyOperation))
  vendorDivider.foreach(endpoint => forwardRequest(endpoint.io, vendorDivideSelected, divideOperation))
  directMultiplier.foreach(endpoint => forwardRequest(endpoint.io, directMultiplySelected, multiplyOperation))
  directDivider.foreach(endpoint => forwardRequest(endpoint.io, directDivideSelected, divideOperation))

  private val endpointSelections = Seq(
    vendorMultiplier.map(vendorMultiplySelected -> _),
    vendorDivider.map(vendorDivideSelected -> _),
    directMultiplier.map(directMultiplySelected -> _),
    directDivider.map(directDivideSelected -> _)
  ).flatten
  private val responseSources = endpointSelections.map(_._2.io.resp)
  require(responseSources.nonEmpty, "MulDivAlu requires at least one response source")

  // 请求仍由当拍选择的端点决定。完成表给每项请求分配了 tag，因此同一端点可按 II
  // 连续接收独立请求；多个端点恰好同拍完成时，仲裁器只让其中一项握手，另一项保持
  // valid 等待下一拍，端点自身的响应 FIFO 保证结果不会丢失。
  io.req.ready := MuxCase(false.B,
    endpointSelections.map { case (selected, endpoint) => selected -> endpoint.io.req.ready })
  val responseArbiter = Module(new Arbiter(new ArithmeticResponse(width, config.tagWidth), responseSources.size))
  responseSources.zipWithIndex.foreach { case (source, index) =>
    responseArbiter.io.in(index) <> source
  }
  io.resp <> responseArbiter.io.out
}
