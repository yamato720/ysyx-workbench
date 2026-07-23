package scpu

import chisel3._
import chisel3.util._
import scpu.protocol.ArithmeticAssistPort

/** RV32M/RV64M 执行外壳。它译码架构 ALU 操作，发射可复用乘除法算子并汇聚响应。
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
  routes: OperatorRouteConfig = OperatorRouteConfig()
) extends Module {
  require(width == 32 || width == 64, s"MulDivAlu supports RV32/RV64, got width=$width")
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new AluRequest(width, config.tagWidth)))
    val resp = Decoupled(new ArithmeticResponse(width, config.tagWidth))
    val assist = new ArithmeticAssistPort(width)
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
  private val allRoutes = multiplyRoutes ++ divideRoutes

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
  private val fallbackTargets: Set[OperatorRouteTarget] = Set(OperatorRouteTarget.HostFallback)
  private val vendorMultiplySelected = if (routeEnabled) selectedFor(multiplyRoutes, vendorTargets) else MulDivAlu.isMultiply(io.req.bits.aluOp)
  private val vendorDivideSelected = if (routeEnabled) selectedFor(divideRoutes, vendorTargets) else MulDivAlu.isDivide(io.req.bits.aluOp)
  private val directMultiplySelected = if (routeEnabled) selectedFor(multiplyRoutes, directTargets) else false.B
  private val directDivideSelected = if (routeEnabled) selectedFor(divideRoutes, directTargets) else false.B
  private val fallbackSelected = if (routeEnabled) selectedFor(allRoutes, fallbackTargets) else false.B

  private val vendorMultiplier = if (!routeEnabled || hasRoute(multiplyRoutes, vendorTargets)) Some(Module(new IntegerMultiplierOperator(
    width, config.implementation, config.tagWidth, config.multiplyTiming,
    firstRoute(multiplyRoutes, vendorTargets, config.multiplyAdapterModuleName).moduleName))) else None
  private val vendorDivider = if (!routeEnabled || hasRoute(divideRoutes, vendorTargets)) Some(Module(new IntegerDividerOperator(
    width, config.implementation, config.tagWidth, config.divideTiming,
    firstRoute(divideRoutes, vendorTargets, config.dividerAdapterModuleName).moduleName))) else None
  private val directMultiplier = if (routeEnabled && hasRoute(multiplyRoutes, directTargets)) Some(Module(new IntegerMultiplierOperator(
    width, ComputeUnitConfig(backend = ComputeBackend.Builtin), config.tagWidth, config.multiplyTiming,
    "cycle-model"))) else None
  private val directDivider = if (routeEnabled && hasRoute(divideRoutes, directTargets)) Some(Module(new IntegerDividerOperator(
    width, ComputeUnitConfig(backend = ComputeBackend.Builtin), config.tagWidth, config.divideTiming,
    "cycle-model"))) else None
  private val fallback = if (routeEnabled && hasRoute(allRoutes, fallbackTargets)) {
    val reason = allRoutes.collectFirst {
      case (operation, _) if routes.route(operation).target == OperatorRouteTarget.HostFallback =>
        routes.route(operation).fallbackReason
    }.get
    Some(Module(new HostFallbackOperator(width, config.tagWidth, ArithmeticRouteDomain.Integer, reason)))
  } else None
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
  fallback.foreach(endpoint => forwardRequest(endpoint.io.arithmetic, fallbackSelected, io.req.bits.aluOp))
  io.req.ready := MuxCase(false.B, Seq(
    vendorMultiplier.map(endpoint => vendorMultiplySelected -> endpoint.io.req.ready),
    vendorDivider.map(endpoint => vendorDivideSelected -> endpoint.io.req.ready),
    directMultiplier.map(endpoint => directMultiplySelected -> endpoint.io.req.ready),
    directDivider.map(endpoint => directDivideSelected -> endpoint.io.req.ready),
    fallback.map(endpoint => fallbackSelected -> endpoint.io.arithmetic.req.ready)
  ).flatten)

  private val responseSources = Seq(
    vendorMultiplier.map(_.io.resp), vendorDivider.map(_.io.resp),
    directMultiplier.map(_.io.resp), directDivider.map(_.io.resp), fallback.map(_.io.arithmetic.resp)
  ).flatten
  private val responses = Module(new RRArbiter(new ArithmeticResponse(width, config.tagWidth), responseSources.size))
  responseSources.zipWithIndex.foreach { case (source, index) => responses.io.in(index) <> source }
  io.resp <> responses.io.out

  fallback match {
    case Some(endpoint) =>
      io.assist.request.valid := endpoint.io.assist.request.valid
      io.assist.request.bits := endpoint.io.assist.request.bits
      endpoint.io.assist.request.ready := io.assist.request.ready
      endpoint.io.assist.response.valid := io.assist.response.valid
      endpoint.io.assist.response.bits := io.assist.response.bits
      io.assist.response.ready := endpoint.io.assist.response.ready
      io.assist.busy := endpoint.io.assist.busy
    case None =>
      io.assist.request.valid := false.B
      io.assist.request.bits := 0.U.asTypeOf(io.assist.request.bits)
      io.assist.response.ready := true.B
      io.assist.busy := false.B
  }
}
