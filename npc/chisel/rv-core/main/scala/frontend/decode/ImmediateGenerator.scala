package scpu

import chisel3._
import chisel3.util._

/** ID 阶段针对五种基础 RISC-V 指令格式的立即数译码器。 */
object RiscvImmediateGenerator {
  def apply(instruction: UInt, width: Int): UInt = {
    val opcode = instruction(6, 0)
    val iImm = Cat(Fill(width - 12, instruction(31)), instruction(31, 20))
    val sImm = Cat(Fill(width - 12, instruction(31)), instruction(31, 25), instruction(11, 7))
    val bImm = Cat(Fill(width - 13, instruction(31)), instruction(31), instruction(7), instruction(30, 25), instruction(11, 8), 0.U(1.W))
    val uImm =
      if (width > 32) Cat(Fill(width - 32, instruction(31)), instruction(31, 12), 0.U(12.W))
      else Cat(instruction(31, 12), 0.U(12.W))
    val jImm = Cat(Fill(width - 21, instruction(31)), instruction(31), instruction(19, 12), instruction(20), instruction(30, 21), 0.U(1.W))

    MuxLookup(opcode, 0.U(width.W))(Seq(
      "b0010011".U -> iImm,
      "b0000011".U -> iImm,
      "b0000111".U -> iImm,
      "b1100111".U -> iImm,
      "b1110011".U -> iImm,
      "b0011011".U -> iImm,
      "b0100011".U -> sImm,
      "b0100111".U -> sImm,
      "b1100011".U -> bImm,
      "b0110111".U -> uImm,
      "b0010111".U -> uImm,
      "b1101111".U -> jImm
    ))
  }
}
