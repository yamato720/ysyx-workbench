package accelerators.common

import chisel3._
import chisel3.util._
import npc.ip.axi.Axi4ReadWriteMasterIO

/** 单路独立 AXI4 读写单元的统一接口。 */
class IndependentAxiReadWritePortIO[
  ReadRequest <: Data,
  ReadOutput <: Data,
  WriteRequest <: Data,
  WriteInput <: Data
](
  readRequestGenerator: () => ReadRequest,
  readOutputGenerator: () => ReadOutput,
  writeRequestGenerator: () => WriteRequest,
  writeInputGenerator: () => WriteInput,
  addrWidth: Int,
  dataWidth: Int,
  idWidth: Int
) extends Bundle {
  val readRequest = Flipped(Decoupled(readRequestGenerator()))
  val readOutput = Decoupled(readOutputGenerator())
  val writeRequest = Flipped(Decoupled(writeRequestGenerator()))
  val writeInput = Flipped(Decoupled(writeInputGenerator()))
  val axi = new Axi4ReadWriteMasterIO(addrWidth, dataWidth, idWidth)
  val readIdle = Output(Bool())
  val readBusy = Output(Bool())
  val readDone = Output(Bool())
  val readError = Output(Bool())
  val writeIdle = Output(Bool())
  val writeBusy = Output(Bool())
  val writeDone = Output(Bool())
  val writeError = Output(Bool())
}

/** 多路独立 AXI4 读写端口的顶层接口。 */
class IndependentAxiReadWritePortsIO[
  ReadRequest <: Data,
  ReadOutput <: Data,
  WriteRequest <: Data,
  WriteInput <: Data
](
  portCount: Int,
  readRequestGenerator: () => ReadRequest,
  readOutputGenerator: () => ReadOutput,
  writeRequestGenerator: () => WriteRequest,
  writeInputGenerator: () => WriteInput,
  addrWidth: Int,
  dataWidth: Int,
  idWidth: Int
) extends Bundle {
  val readRequest = Vec(portCount, Flipped(Decoupled(readRequestGenerator())))
  val readOutput = Vec(portCount, Decoupled(readOutputGenerator()))
  val writeRequest = Vec(portCount, Flipped(Decoupled(writeRequestGenerator())))
  val writeInput = Vec(portCount, Flipped(Decoupled(writeInputGenerator())))
  val axi = Vec(portCount, new Axi4ReadWriteMasterIO(addrWidth, dataWidth, idWidth))
  val readIdle = Output(Vec(portCount, Bool()))
  val readBusy = Output(Vec(portCount, Bool()))
  val readDone = Output(Vec(portCount, Bool()))
  val readError = Output(Vec(portCount, Bool()))
  val writeIdle = Output(Vec(portCount, Bool()))
  val writeBusy = Output(Vec(portCount, Bool()))
  val writeDone = Output(Vec(portCount, Bool()))
  val writeError = Output(Vec(portCount, Bool()))
}

/** 可扩展的独立 AXI4 读写基础模块；各 lane 共享接口形状，但事务状态互不耦合。 */
abstract class IndependentAxiReadWritePorts[
  ReadRequest <: Data,
  ReadOutput <: Data,
  WriteRequest <: Data,
  WriteInput <: Data
](
  portCount: Int,
  readRequestGenerator: () => ReadRequest,
  readOutputGenerator: () => ReadOutput,
  writeRequestGenerator: () => WriteRequest,
  writeInputGenerator: () => WriteInput,
  addrWidth: Int,
  dataWidth: Int,
  idWidth: Int,
  portFactory: () => Module {
    val io: IndependentAxiReadWritePortIO[ReadRequest, ReadOutput, WriteRequest, WriteInput]
  }
) extends Module {
  require(portCount > 0, s"独立 AXI4 读写端口数量必须为正数，实际为 $portCount")

  val io = IO(new IndependentAxiReadWritePortsIO(
    portCount,
    readRequestGenerator,
    readOutputGenerator,
    writeRequestGenerator,
    writeInputGenerator,
    addrWidth,
    dataWidth,
    idWidth
  ))

  private val ports = Seq.fill(portCount)(Module(portFactory()))
  ports.zipWithIndex.foreach { case (port, index) =>
    io.readRequest(index) <> port.io.readRequest
    io.readOutput(index) <> port.io.readOutput
    io.writeRequest(index) <> port.io.writeRequest
    io.writeInput(index) <> port.io.writeInput
    io.axi(index) <> port.io.axi
    io.readIdle(index) := port.io.readIdle
    io.readBusy(index) := port.io.readBusy
    io.readDone(index) := port.io.readDone
    io.readError(index) := port.io.readError
    io.writeIdle(index) := port.io.writeIdle
    io.writeBusy(index) := port.io.writeBusy
    io.writeDone(index) := port.io.writeDone
    io.writeError(index) := port.io.writeError
  }
}
