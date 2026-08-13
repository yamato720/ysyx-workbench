package accelerators.common

import chisel3._
import chisel3.util._
import npc.ip.axi.Axi4WriteMasterIO

/** 单路独立 AXI4 写入单元的统一接口。 */
class IndependentAxiWritePortIO[Request <: Data, InputData <: Data](
  requestGenerator: () => Request,
  inputGenerator: () => InputData,
  addrWidth: Int,
  dataWidth: Int,
  idWidth: Int
) extends Bundle {
  val request = Flipped(Decoupled(requestGenerator()))
  val axi = new Axi4WriteMasterIO(addrWidth, dataWidth, idWidth)
  val input = Flipped(Decoupled(inputGenerator()))
  val idle = Output(Bool())
  val busy = Output(Bool())
  val done = Output(Bool())
  val error = Output(Bool())
}

/** 多路独立 AXI4 写入端口的顶层接口。 */
class IndependentAxiWritePortsIO[Request <: Data, InputData <: Data](
  portCount: Int,
  requestGenerator: () => Request,
  inputGenerator: () => InputData,
  addrWidth: Int,
  dataWidth: Int,
  idWidth: Int
) extends Bundle {
  val request = Vec(portCount, Flipped(Decoupled(requestGenerator())))
  val axi = Vec(portCount, new Axi4WriteMasterIO(addrWidth, dataWidth, idWidth))
  val input = Vec(portCount, Flipped(Decoupled(inputGenerator())))
  val idle = Output(Vec(portCount, Bool()))
  val busy = Output(Vec(portCount, Bool()))
  val done = Output(Vec(portCount, Bool()))
  val error = Output(Vec(portCount, Bool()))
}

/** 可扩展的独立 AXI4 写入基础模块；每一路保持自己的请求、数据和响应状态。 */
abstract class IndependentAxiWritePorts[Request <: Data, InputData <: Data](
  portCount: Int,
  requestGenerator: () => Request,
  inputGenerator: () => InputData,
  addrWidth: Int,
  dataWidth: Int,
  idWidth: Int,
  portFactory: () => Module { val io: IndependentAxiWritePortIO[Request, InputData] }
) extends Module {
  require(portCount > 0, s"独立 AXI4 写入端口数量必须为正数，实际为 $portCount")

  val io = IO(new IndependentAxiWritePortsIO(
    portCount,
    requestGenerator,
    inputGenerator,
    addrWidth,
    dataWidth,
    idWidth
  ))

  private val ports = Seq.fill(portCount)(Module(portFactory()))
  ports.zipWithIndex.foreach { case (port, index) =>
    io.request(index) <> port.io.request
    io.axi(index) <> port.io.axi
    io.input(index) <> port.io.input
    io.idle(index) := port.io.idle
    io.busy(index) := port.io.busy
    io.done(index) := port.io.done
    io.error(index) := port.io.error
  }
}
