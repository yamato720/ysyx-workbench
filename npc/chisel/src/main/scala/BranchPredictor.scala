package scpu
import chisel3._
import  chisel3.util._

/*
Next Line Predictor：顺序和简单跳转。
RAS：return地址预测，针对函数调用结构。
TAGE：复杂条件分支方向预测，提升整体分支命中率。
Indirect Predictor：间接跳转目标预测，适配多态和动态分发场景。
*/


class BranchPredictor(NLP:Int = 1, RAS:Int = 1, TAGE:Int = 1, IP:Int = 1, Debug:Boolean = false) extends Module {
  val io = IO(new Bundle{
    // 输入：当前指令地址，分支类型，实际跳转结果（用于更新预测器）
    val pc_in = Input(UInt(32.W))
    val predict_target = Output(UInt(32.W))
  })

  io.predict_target := io.pc_in
}
