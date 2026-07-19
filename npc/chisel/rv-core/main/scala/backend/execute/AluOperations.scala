package scpu

import chisel3._
import chisel3.util._

/** 后端和一个 ISA 执行 ALU 之间传递的请求。`aluOp` 刻意不进入 `compute/`：
  * ALU 会在发射可复用算子前把它映射为算子私有的 `ArithmeticRequest.operation`。
  */
class AluRequest(width: Int, tagWidth: Int) extends Bundle {
  val operandA = UInt(width.W)
  val operandB = UInt(width.W)
  val operandC = UInt(width.W)
  val aluOp = UInt(NpcAluOp.width.W)
  val roundingMode = UInt(3.W)
  val pc = UInt(width.W)
  val instruction = UInt(32.W)
  val fcsr = UInt(8.W)
  val tag = UInt(tagWidth.W)
}

/** ISA ALU 传输接口：请求携带架构操作，响应携带通用结果和异常标志。
  */
class AluIO(width: Int, tagWidth: Int) extends Bundle {
  val req = Flipped(Decoupled(new AluRequest(width, tagWidth)))
  val resp = Decoupled(new ArithmeticResponse(width, tagWidth))
}

object NpcAluOp {
  /**
    * `aluCtrl` 是五位的执行单元私有协议字段。整数 ALU、M 扩展单元和 F 单元
    * 复用同一根连线，因此数值仅与 `NpcExecutionUnit` 结合时有意义。保持三个
    * 命名空间显式分离，避免在一个扁平对象中维护互不相关的二进制字面量。
    *
    * 声明顺序即稳定的硬件/DPI 编码。ChiselEnum 还会把符号名称写入生成的
    * FIRRTL 元数据。
    */
  val width = 5

  object Integer extends ChiselEnum {
    val ADD, SUB, AND, OR, XOR, SLL, SRL, SRA, SLT, SLTU,
      BEQ, BNE, BLT, BGE, BLTU, BGEU, AUIPC, LUI, JAL, JALR,
      ADDW, SUBW, SLLW, SRLW, SRAW = Value
  }

  object MulDiv extends ChiselEnum {
    val MUL, MULH, MULHSU, MULHU, DIV, DIVU, REM, REMU,
      MULW, DIVW, DIVUW, REMW, REMUW = Value
  }

  object Floating extends ChiselEnum {
    val FADD, FSUB, FMUL, FDIV, FSQRT, FMADD, FMSUB, FNMSUB, FNMADD,
      FSGNJ, FSGNJN, FSGNJX, FMIN, FMAX, FEQ, FLT, FLE,
      FCVT_W, FCVT_WU, FCVT_L, FCVT_LU, FCVT_S_W, FCVT_S_WU,
      FCVT_S_L, FCVT_S_LU, FMV_X_W, FCLASS, FMV_W_X = Value
  }

  require(Integer.getWidth <= width, "Integer ALU encoding exceeds aluCtrl width")
  require(MulDiv.getWidth <= width, "M-extension encoding exceeds aluCtrl width")
  require(Floating.getWidth <= width, "Floating-point encoding exceeds aluCtrl width")
}

object NpcBranchResult {
  def notTaken = 0.U(3.W)
  def pcImmediate = 1.U(3.W)
  def rs1Immediate = 2.U(3.W)
}

object NpcCsrOp {
  val width = 2

  def write = "b00".U(width.W)
  def set = "b01".U(width.W)
  def clear = "b10".U(width.W)
}

/** 由 ID 译码选择的互斥 EX 结果生产者。
  *
  * 它替代旧的可扩展结果位掩码：当前每条已译码指令恰好属于一个执行单元，使用
  * 枚举可明确流水线约定并避免重叠选择结果。
  */
object NpcExecutionUnit {
  val width = 2

  def integer = "b00".U(width.W)
  def multiply = "b01".U(width.W)
  def divide = "b10".U(width.W)
  def floating = "b11".U(width.W)
}
