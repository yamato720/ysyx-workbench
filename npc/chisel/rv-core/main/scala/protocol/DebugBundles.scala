package scpu.protocol

import chisel3._
import chisel3.util.log2Ceil
import scpu.ISAConfig

/** 取指和译码产生的调试遥测信息。 */
class NpcFrontendDebugBundle(cfg: ISAConfig) extends Bundle {
  val pcWriteEnable = Bool()
  val fetchDecodeFire = Bool()
  val currentPc = UInt(cfg.xlen.W)
  val nextArchitecturalPc = UInt(cfg.xlen.W)
  val frontendInstruction = UInt(32.W)
  val decodeImmediate = UInt(cfg.xlen.W)
  val decodeOpcode = UInt(7.W)
  val decodeFunct3 = UInt(3.W)
  val decodeFunct7 = UInt(7.W)
  val fetchAxiWaitCycles = UInt(64.W)
  val redirectFlushCount = UInt(64.W)
  val fetchBusy = Bool()
  val dispatchBackpressured = Bool()
}

/** 按序执行和提交后端产生的调试遥测信息。 */
class NpcBackendDebugBundle(cfg: ISAConfig) extends Bundle {
  val registers = Vec(32, UInt(cfg.xlen.W))
  val floatingRegisters = Vec(32, UInt(cfg.xlen.W))
  val fcsr = UInt(8.W)
  val mstatus = UInt(cfg.xlen.W)
  val mcause = UInt(cfg.xlen.W)
  val mtvec = UInt(cfg.xlen.W)

  val decodeExecuteFire = Bool()
  val executeMemoryFire = Bool()
  val commitValid = Bool()

  val mepc = UInt(cfg.xlen.W)
  val executeTrapEnable = Bool()
  val commitTrapEnable = Bool()
  val executeCsrEnable = Bool()
  val commitCsrAllow = Bool()
  val commitCsrAddress = UInt(12.W)
  val commitTrapEpc = UInt(cfg.xlen.W)

  val commitPc = UInt(cfg.xlen.W)
  val commitInstruction = UInt(32.W)
  val commitNextPc = UInt(cfg.xlen.W)

  val cycleCount = UInt(64.W)
  val commitFetchCycles = UInt(64.W)
  val commitDecodeCycles = UInt(64.W)
  val commitExecuteCycles = UInt(64.W)
  val commitMemoryCycles = UInt(64.W)
  val commitWritebackCycles = UInt(64.W)
  val pipelineFeatures = UInt(3.W)
  val idStallCycles = UInt(64.W)
  val executeStallCycles = UInt(64.W)
  val memoryStallCycles = UInt(64.W)

  val coreBusy = Bool()
  val executeAluResult = UInt(cfg.xlen.W)
  val memoryResult = UInt(cfg.xlen.W)

  val dispatchBackpressured = Bool()
  val idExBackpressured = Bool()
  val exMemBackpressured = Bool()
  val memoryWaitingForLsu = Bool()
  val lsuTransactionActive = Bool()
  val serialExecuteActive = Bool()
}

/** 随内核调试遥测导出的 AXI 读通道观测值。 */
class NpcMasterDebugBundle(addrWidth: Int, dataWidth: Int) extends Bundle {
  val arValid = Bool()
  val arReady = Bool()
  val arAddress = UInt(addrWidth.W)
  val rValid = Bool()
  val rReady = Bool()
  val rData = UInt(dataWidth.W)
}

/** 前端、后端和主总线遥测信息在内核级的聚合。 */
class NpcCoreDebugBundle(
  cfg: ISAConfig,
  masterAddrWidth: Int,
  masterDataWidth: Int
) extends Bundle {
  val frontend = new NpcFrontendDebugBundle(cfg)
  val backend = new NpcBackendDebugBundle(cfg)
  val master = new NpcMasterDebugBundle(masterAddrWidth, masterDataWidth)

  val backpressureReasons = UInt(9.W)
  val coreBusy = Bool()

  // 预留探针维持既有的零值遥测语义。
  val bufferIndex = UInt(log2Ceil(10).W)
  val bufferAccessCount = UInt((log2Ceil(10) + 1).W)
  val waitCycles = UInt(32.W)
  val addressLow = UInt(cfg.xlen.W)
  val addressHigh = UInt(cfg.xlen.W)
  val pcBase = UInt(cfg.xlen.W)
  val instructionHighByte = UInt(8.W)
  val instructionLowByte = UInt(8.W)
  val extendedRegisters = Vec(128, UInt(cfg.xlen.W))
}
