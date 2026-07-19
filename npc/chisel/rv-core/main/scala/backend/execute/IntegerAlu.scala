package scpu

import chisel3._
import chisel3.util._

/** RV32I/RV64I 共用的组合整数执行路径。
  *
  * 本单元没有流水线专属状态。弹性流水线和默认串行内核共用它，因此算术、分支比较
  * 与跳转链接行为不会在两种执行模式间漂移。多周期 RV M 扩展由 [[MulDivAlu]] 处理，
  * EX 将其与该固定时延单元并列调度。
  */
class IntegerAlu(width: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(width.W))
    val b = Input(UInt(width.W))
    val pc = Input(UInt(width.W))
    val control = Input(UInt(NpcAluOp.width.W))
    val result = Output(UInt(width.W))
    val branchTaken = Output(UInt(3.W))
  })

  val result = WireDefault(0.U(width.W))
  val branchTaken = WireDefault(NpcBranchResult.notTaken)
  val shiftWidth = log2Ceil(width)

  switch(io.control) {
    is(NpcAluOp.Integer.ADD.asUInt) { result := io.a + io.b }
    is(NpcAluOp.Integer.SUB.asUInt) { result := io.a - io.b }
    is(NpcAluOp.Integer.AND.asUInt) { result := io.a & io.b }
    is(NpcAluOp.Integer.OR.asUInt) { result := io.a | io.b }
    is(NpcAluOp.Integer.XOR.asUInt) { result := io.a ^ io.b }
    is(NpcAluOp.Integer.SLL.asUInt) { result := (io.a << io.b(shiftWidth - 1, 0))(width - 1, 0) }
    is(NpcAluOp.Integer.SRL.asUInt) { result := io.a >> io.b(shiftWidth - 1, 0) }
    is(NpcAluOp.Integer.SRA.asUInt) { result := (io.a.asSInt >> io.b(shiftWidth - 1, 0)).asUInt }
    is(NpcAluOp.Integer.SLT.asUInt) { result := (io.a.asSInt < io.b.asSInt).asUInt }
    is(NpcAluOp.Integer.SLTU.asUInt) { result := (io.a < io.b).asUInt }
    is(NpcAluOp.Integer.BEQ.asUInt) { branchTaken := Mux(io.a === io.b, NpcBranchResult.pcImmediate, NpcBranchResult.notTaken) }
    is(NpcAluOp.Integer.BNE.asUInt) { branchTaken := Mux(io.a =/= io.b, NpcBranchResult.pcImmediate, NpcBranchResult.notTaken) }
    is(NpcAluOp.Integer.BLT.asUInt) { branchTaken := Mux(io.a.asSInt < io.b.asSInt, NpcBranchResult.pcImmediate, NpcBranchResult.notTaken) }
    is(NpcAluOp.Integer.BGE.asUInt) { branchTaken := Mux(io.a.asSInt >= io.b.asSInt, NpcBranchResult.pcImmediate, NpcBranchResult.notTaken) }
    is(NpcAluOp.Integer.BLTU.asUInt) { branchTaken := Mux(io.a < io.b, NpcBranchResult.pcImmediate, NpcBranchResult.notTaken) }
    is(NpcAluOp.Integer.BGEU.asUInt) { branchTaken := Mux(io.a >= io.b, NpcBranchResult.pcImmediate, NpcBranchResult.notTaken) }
    is(NpcAluOp.Integer.AUIPC.asUInt) { result := io.pc + io.b }
    is(NpcAluOp.Integer.LUI.asUInt) { result := io.b }
    is(NpcAluOp.Integer.JAL.asUInt) {
      result := io.pc + 4.U
      branchTaken := NpcBranchResult.pcImmediate
    }
    is(NpcAluOp.Integer.JALR.asUInt) {
      result := io.pc + 4.U
      branchTaken := NpcBranchResult.rs1Immediate
    }
  }

  if (width == 64) {
    val wordResult = WireDefault(0.U(32.W))
    val wordOperation = WireDefault(false.B)
    switch(io.control) {
      is(NpcAluOp.Integer.ADDW.asUInt) {
        wordResult := io.a(31, 0) + io.b(31, 0)
        wordOperation := true.B
      }
      is(NpcAluOp.Integer.SUBW.asUInt) {
        wordResult := io.a(31, 0) - io.b(31, 0)
        wordOperation := true.B
      }
      is(NpcAluOp.Integer.SLLW.asUInt) {
        wordResult := (io.a(31, 0) << io.b(4, 0))(31, 0)
        wordOperation := true.B
      }
      is(NpcAluOp.Integer.SRLW.asUInt) {
        wordResult := io.a(31, 0) >> io.b(4, 0)
        wordOperation := true.B
      }
      is(NpcAluOp.Integer.SRAW.asUInt) {
        wordResult := (io.a(31, 0).asSInt >> io.b(4, 0)).asUInt
        wordOperation := true.B
      }
    }
    when(wordOperation) {
      result := Cat(Fill(32, wordResult(31)), wordResult)
    }
  }

  io.result := result
  io.branchTaken := branchTaken
}
