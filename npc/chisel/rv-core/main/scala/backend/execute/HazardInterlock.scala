package scpu

import chisel3._
import chisel3.util._

/** 对 ID RAW 冒险检测可见的一条寄存器结果生产指令。 */
class HazardProducer extends Bundle {
  val valid = Bool()
  val writesRd = Bool()
  val rd = UInt(5.W)
  // 各启用旁路是否可选择该生产者的架构值。数值尚未就绪时生产者仍保持有效，
  // 以免误选同一 rd 的更老写入。
  val idForwardAvailable = Bool()
  val executeForwardNextCycleAvailable = Bool()
}

/** 一条 GPR 前递候选项，按生产者由新到旧排序。 */
class ForwardingCandidate(xlen: Int) extends Bundle {
  val valid = Bool()
  val writesRd = Bool()
  val rd = UInt(5.W)
  val data = UInt(xlen.W)
  val dataValid = Bool()
}

/** RAW 检测器：仅当启用的前递路径可提供数值时，才放行最新的匹配写者。
  */
class HazardUnit(producerCount: Int = 5) extends Module {
  val io = IO(new Bundle {
    val enableInterlock = Input(Bool())
    val enableIdForwarding = Input(Bool())
    val enableExecuteForwarding = Input(Bool())
    val usesRs1 = Input(Bool())
    val usesRs2 = Input(Bool())
    val rs1 = Input(UInt(5.W))
    val rs2 = Input(UInt(5.W))
    val producers = Input(Vec(producerCount, new HazardProducer))
    val rs1Hazard = Output(Bool())
    val rs2Hazard = Output(Bool())
    val rs1Resolved = Output(Bool())
    val rs2Resolved = Output(Bool())
    val stall = Output(Bool())
  })

  private def sourceMatches(source: UInt, used: Bool): Seq[Bool] =
    io.producers.map { producer =>
      used && source =/= 0.U && producer.valid && producer.writesRd &&
        producer.rd =/= 0.U && producer.rd === source
    }

  private def youngestMatchValue(matches: Seq[Bool], value: HazardProducer => Bool): Bool =
    io.producers.zip(matches).foldRight(false.B) { case ((producer, matched), older) =>
      Mux(matched, value(producer), older)
    }

  /** 判断一个源寄存器是否依赖尚未提交的旧指令。 */
  def sourceHazard(source: UInt, used: Bool): Bool = {
    val producerMatches = io.producers.map { producer =>
      producer.valid && producer.writesRd && producer.rd =/= 0.U && producer.rd === source
    }
    val matchingWriter = producerMatches.reduce(_ || _)
    used && source =/= 0.U && matchingWriter
  }

  private def sourceResolved(source: UInt, used: Bool): Bool = {
    val matches = sourceMatches(source, used)
    val matchingWriter = matches.reduce(_ || _)
    val idAvailable = youngestMatchValue(matches, _.idForwardAvailable)
    val executeAvailable = youngestMatchValue(matches, _.executeForwardNextCycleAvailable)

    !matchingWriter || (io.enableIdForwarding && idAvailable) ||
      (io.enableExecuteForwarding && executeAvailable)
  }

  io.rs1Hazard := sourceHazard(io.rs1, io.usesRs1)
  io.rs2Hazard := sourceHazard(io.rs2, io.usesRs2)
  io.rs1Resolved := sourceResolved(io.rs1, io.usesRs1)
  io.rs2Resolved := sourceResolved(io.rs2, io.usesRs2)
  io.stall := io.enableInterlock &&
    ((io.rs1Hazard && !io.rs1Resolved) || (io.rs2Hazard && !io.rs2Resolved))
}

/** ID 与 EX 的 GPR 前递 mux；候选项按由新到旧排序。 */
class ForwardingUnit(xlen: Int, candidateCount: Int = 5) extends Module {
  val io = IO(new Bundle {
    val enableIdForwarding = Input(Bool())
    val enableExecuteForwarding = Input(Bool())
    val idRs1 = Input(UInt(5.W))
    val idRs2 = Input(UInt(5.W))
    val idUsesRs1 = Input(Bool())
    val idUsesRs2 = Input(Bool())
    val idRs1Data = Input(UInt(xlen.W))
    val idRs2Data = Input(UInt(xlen.W))
    val executeRs1 = Input(UInt(5.W))
    val executeRs2 = Input(UInt(5.W))
    val executeUsesRs1 = Input(Bool())
    val executeUsesRs2 = Input(Bool())
    val executeRs1Data = Input(UInt(xlen.W))
    val executeRs2Data = Input(UInt(xlen.W))
    val idCandidates = Input(Vec(candidateCount, new ForwardingCandidate(xlen)))
    val executeCandidates = Input(Vec(candidateCount, new ForwardingCandidate(xlen)))
    val idRs1Forwarded = Output(UInt(xlen.W))
    val idRs2Forwarded = Output(UInt(xlen.W))
    val executeRs1Forwarded = Output(UInt(xlen.W))
    val executeRs2Forwarded = Output(UInt(xlen.W))
  })

  private def forwarded(
    source: UInt,
    used: Bool,
    base: UInt,
    enabled: Bool,
    candidates: Vec[ForwardingCandidate]
  ): UInt = {
    val matches = candidates.map { candidate =>
      used && source =/= 0.U && candidate.valid && candidate.writesRd &&
        candidate.rd =/= 0.U && candidate.rd === source
    }
    // 即使数值尚未可用，更新的匹配写者也会阻断更老候选项；冒险单元会阻止该情形
    // 消费过期的基准值。
    val selected = candidates.zip(matches).foldRight(base) { case ((candidate, matched), older) =>
      Mux(matched, Mux(candidate.dataValid, candidate.data, base), older)
    }
    Mux(enabled, selected, base)
  }

  io.idRs1Forwarded := forwarded(
    io.idRs1, io.idUsesRs1, io.idRs1Data, io.enableIdForwarding, io.idCandidates)
  io.idRs2Forwarded := forwarded(
    io.idRs2, io.idUsesRs2, io.idRs2Data, io.enableIdForwarding, io.idCandidates)
  io.executeRs1Forwarded := forwarded(
    io.executeRs1,
    io.executeUsesRs1,
    io.executeRs1Data,
    io.enableExecuteForwarding,
    io.executeCandidates
  )
  io.executeRs2Forwarded := forwarded(
    io.executeRs2,
    io.executeUsesRs2,
    io.executeRs2Data,
    io.enableExecuteForwarding,
    io.executeCandidates
  )
}
