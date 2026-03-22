package scpu
import chisel3._
import chisel3.stage.ChiselStage


object Elaborate extends App {
  println("正在生成Verilog 文件...")
  (new chisel3.stage.ChiselStage).emitVerilog(
    new CPU(Width = 64, Debug = true),  // 启用 Debug 模式以生成完整的内部逻辑
    Array(
      "--target-dir", "./generated",
      "--output-file", "CPU_64"
    )
  )
  println("生成完成！")
}

// DPI-C mode for Verilator simulation with external memory
object ElaborateDPI extends App {
  println("正在生成 DPI-C 模式的 Verilog 文件...")
  (new chisel3.stage.ChiselStage).emitVerilog(
    new CPU(Width = 64, Debug = true, useDPI = true, M_Extension = true),  // 启用 DPI-C 模式
    Array(
      "--target-dir", "./generated-dpi",
      "--output-file", "CPU_64_DPI"
    )
  )
  println("DPI-C 模式 Verilog 生成完成！")
}
