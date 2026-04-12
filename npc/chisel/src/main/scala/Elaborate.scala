package scpu
import chisel3._
import chisel3.stage.ChiselStage


object Elaborate extends App {
  val cfg = ISAConfig()   // ← 在这里修改配置，例如 ISAConfig(xlen = 32)
  println("正在生成Verilog 文件...")
  (new chisel3.stage.ChiselStage).emitVerilog(
    new CPU(Debug = true, cfg = cfg),
    Array(
      "--target-dir", "./generated",
      "--output-file", "CPU_64"
    )
  )
  println("生成完成！")
}

// DPI-C mode for Verilator simulation with external memory
object ElaborateDPI extends App {
  val cfg = ISAConfig(M = true)   // ← 在这里修改配置，加新扩展只改这一行
  println("正在生成 DPI-C 模式的 Verilog 文件...")
  (new chisel3.stage.ChiselStage).emitVerilog(
    new CPU(Debug = true, useDPI = true, cfg = cfg),
    Array(
      "--target-dir", "./generated-dpi",
      "--output-file", "CPU_64_DPI"
    )
  )
  println("DPI-C 模式 Verilog 生成完成！")
}
