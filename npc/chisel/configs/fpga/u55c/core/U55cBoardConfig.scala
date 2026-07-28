package npc.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.npc.fpga.FpgaBoard
import _root_.npc.fpga.FpgaPlatformSettings
import _root_.npc.fpga.{FpgaRuntimeTraceConfig, WithFpgaBoardConfig, WithFpgaClockMHzConfig, WithFpgaPlatformConfig, WithFpgaRuntimeTraceConfig}
import _root_.npc.{FpgaIpAttachment, OperatorIpTimingConfig, WithFpgaIpAttachmentConfig, XilinxIntegerIpAttachment}

/** U55C 复用的 Xilinx 整数 IP attachment；板卡 core 可在此基础上覆盖算子时序。 */
object U55cXilinxIpAttachment {
  def apply(
    timing: OperatorIpTimingConfig = OperatorIpTimingConfig.Default,
    dividerNonBlocking: Boolean = false
  ): XilinxIntegerIpAttachment =
    XilinxIntegerIpAttachment(
      name = "xilinx-u55c",
      arithmeticIp = U55cIpComponents,
      timing = timing,
      dividerIpCycles = 34,
      dividerAdapterCycles = 3,
      dividerNonBlocking = dividerNonBlocking
    )
}

/** U55C 的物理板卡策略，供裸 NPC 和 SoC 终端构造共同复用。 */
class U55cBoardConfig(
  clockMHz: Int = 125,
  ipAttachment: FpgaIpAttachment = U55cXilinxIpAttachment()
) extends CDEConfig(
  new WithFpgaIpAttachmentConfig(ipAttachment) ++
    new WithFpgaClockMHzConfig(clockMHz) ++
    new WithFpgaPlatformConfig(FpgaPlatformSettings(
      board = FpgaBoard.U55c,
      clockMHz = clockMHz,
      memoryHostBase = 0x00000000L,
      controlBase = 0xa0000000L,
      mailboxBase = 0xa0010000L
    )) ++
    new WithFpgaBoardConfig(FpgaBoard.U55c)
)

/** U55C 的 300 MHz 板卡策略。
  *
  * RV64 乘法器改用六级流水切分 DSP 组合链；除法器改为无输出回压的 fixed-latency
  * DivGen，以移除其 Blocking 输出 FIFO 的高扇出 CE 网络。乘除法均维持 II=1。
  */
class U55c300MHzBoardConfig extends U55cBoardConfig(
  clockMHz = 300,
  ipAttachment = U55cXilinxIpAttachment(
    OperatorIpTimingConfig.Default.copy(
      multiply = OperatorIpTimingConfig.Default.multiply.copy(latency = 6)
    ),
    dividerNonBlocking = true
  )
)

/** U55C 300 MHz 调试板卡策略。
  *
  * 该策略的第二个 AXI master 是 v12 硬件 ABI 的一部分。普通 300 MHz Config
  * 保持使用 [[U55c300MHzBoardConfig]]，不会出现 trace HBM 端口。
  */
class U55c300MHzDebugBoardConfig(
  runtimeTrace: FpgaRuntimeTraceConfig = FpgaRuntimeTraceConfig.U55cDebug
) extends CDEConfig(
  new WithFpgaRuntimeTraceConfig(runtimeTrace) ++
    new U55c300MHzBoardConfig
)
