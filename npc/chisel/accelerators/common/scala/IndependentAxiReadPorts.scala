package accelerators.common

import chisel3._
import chisel3.util._
import npc.ip.axi.Axi4ReadMasterIO

/** 单路独立 AXI4 读取单元的统一接口。 */
class IndependentAxiReadPortIO[Request <: Data, OutputData <: Data](
  requestGenerator: () => Request,
  outputGenerator: () => OutputData,
  addrWidth: Int,
  dataWidth: Int,
  idWidth: Int
) extends Bundle {
  val request = Flipped(Decoupled(requestGenerator()))
  val axi = new Axi4ReadMasterIO(addrWidth, dataWidth, idWidth)
  val output = Decoupled(outputGenerator())
  val idle = Output(Bool())
  val busy = Output(Bool())
  val done = Output(Bool())
  val error = Output(Bool())
}

/** 多路独立 AXI4 读取端口的顶层接口。 */
class IndependentAxiReadPortsIO[Request <: Data, OutputData <: Data](
  portCount: Int,
  requestGenerator: () => Request,
  outputGenerator: () => OutputData,
  addrWidth: Int,
  dataWidth: Int,
  idWidth: Int
) extends Bundle {
  val request = Vec(portCount, Flipped(Decoupled(requestGenerator())))
  val axi = Vec(portCount, new Axi4ReadMasterIO(addrWidth, dataWidth, idWidth))
  val output = Vec(portCount, Decoupled(outputGenerator()))
  val idle = Output(Vec(portCount, Bool()))
  val busy = Output(Vec(portCount, Bool()))
  val done = Output(Vec(portCount, Bool()))
  val error = Output(Vec(portCount, Bool()))
}

/**
  * 可扩展的独立 AXI4 读取基础模块。
  *
  * 每一路都由 `portFactory` 创建自己的事务单元；基础模块只展开端口并逐路直连，
  * 不引入仲裁、拼接或跨路反压。产品模块通过继承选择具体读取实现。
  */
abstract class IndependentAxiReadPorts[Request <: Data, OutputData <: Data](
  portCount: Int,
  requestGenerator: () => Request,
  outputGenerator: () => OutputData,
  addrWidth: Int,
  dataWidth: Int,
  idWidth: Int,
  portFactory: () => Module { val io: IndependentAxiReadPortIO[Request, OutputData] }
) extends Module {
  require(portCount > 0, s"独立 AXI4 读取端口数量必须为正数，实际为 $portCount")

  val io = IO(new IndependentAxiReadPortsIO(
    portCount,
    requestGenerator,
    outputGenerator,
    addrWidth,
    dataWidth,
    idWidth
  ))

  private val ports = Seq.fill(portCount)(Module(portFactory()))
  ports.zipWithIndex.foreach { case (port, index) =>
    io.request(index) <> port.io.request
    io.axi(index) <> port.io.axi
    io.output(index) <> port.io.output
    io.idle(index) := port.io.idle
    io.busy(index) := port.io.busy
    io.done(index) := port.io.done
    io.error(index) := port.io.error
  }
}
