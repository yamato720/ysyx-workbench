package npc.ip

import chisel3._

/** 独立 IP 编译边界的最小无状态模块。 */
final class IpModuleBoundary(width: Int) extends Module {
  require(width > 0, s"IP 端口宽度必须为正数，实际为 $width")

  val io = IO(new Bundle {
    val in = Input(UInt(width.W))
    val out = Output(UInt(width.W))
  })

  io.out := io.in
}
