package scpu

import chisel3._

/** 与译码无关的 CSR 分类及浮点 CSR 合法性检查。 */
object CsrAccess {
  def isFloatingAddress(addr: UInt): Bool =
    addr === CsrAddress.fflags.U || addr === CsrAddress.frm.U || addr === CsrAddress.fcsr.U

  def needsFloatingRounding(op: UInt): Bool =
    op === NpcAluOp.Floating.FADD.asUInt || op === NpcAluOp.Floating.FSUB.asUInt ||
      op === NpcAluOp.Floating.FMUL.asUInt || op === NpcAluOp.Floating.FDIV.asUInt ||
      op === NpcAluOp.Floating.FMADD.asUInt || op === NpcAluOp.Floating.FMSUB.asUInt ||
      op === NpcAluOp.Floating.FNMSUB.asUInt || op === NpcAluOp.Floating.FNMADD.asUInt ||
      op === NpcAluOp.Floating.FSQRT.asUInt ||
      op === NpcAluOp.Floating.FCVT_W.asUInt || op === NpcAluOp.Floating.FCVT_WU.asUInt ||
      op === NpcAluOp.Floating.FCVT_L.asUInt || op === NpcAluOp.Floating.FCVT_LU.asUInt ||
      op === NpcAluOp.Floating.FCVT_S_W.asUInt || op === NpcAluOp.Floating.FCVT_S_WU.asUInt ||
      op === NpcAluOp.Floating.FCVT_S_L.asUInt || op === NpcAluOp.Floating.FCVT_S_LU.asUInt

  def hasInvalidFloatingRounding(operation: Bool, op: UInt, rm: UInt, frm: UInt): Bool =
    operation && needsFloatingRounding(op) &&
      (rm === 5.U || rm === 6.U || (rm === 7.U && frm > 4.U))
}
