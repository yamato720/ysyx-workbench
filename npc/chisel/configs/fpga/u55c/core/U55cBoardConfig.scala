package npc.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.npc.fpga.FpgaBoard
import _root_.npc.fpga.FpgaPlatformSettings
import _root_.npc.fpga.{FpgaPerformanceMonitorConfig, FpgaRuntimeSdbConfig, WithFpgaBoardConfig, WithFpgaClockMHzConfig, WithFpgaPerformanceMonitorConfig, WithFpgaPlatformConfig, WithFpgaRuntimeSdbConfig}
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

/** Stock U55C platform contract for HBM-connected RTL kernels. */
object U55cBoardConfig {
  /** The stock shell clock that drives the Vitis RTL-kernel interfaces. */
  val PlatformDataClockMHz = 300
  val SupportedCoreClockMHz: Set[Int] = Set(100, 125, 150, 200, 250, 300)

  def checkedCoreClockMHz(coreClockMHz: Int): Int = {
    require(SupportedCoreClockMHz.contains(coreClockMHz),
      s"U55C core clock must be one of ${SupportedCoreClockMHz.toSeq.sorted.mkString(", ")} MHz, got $coreClockMHz")
    require(coreClockMHz <= PlatformDataClockMHz,
      s"U55C core clock $coreClockMHz MHz exceeds the $PlatformDataClockMHz MHz platform data clock")
    coreClockMHz
  }
}

/** U55C 的物理板卡策略，供裸 NPC 和 SoC 终端构造共同复用。 */
class U55cBoardConfig(
  coreClockMHz: Int = U55cBoardConfig.PlatformDataClockMHz,
  ipAttachment: FpgaIpAttachment = U55cXilinxIpAttachment()
) extends CDEConfig(
  new WithFpgaIpAttachmentConfig(ipAttachment) ++
    new WithFpgaClockMHzConfig(U55cBoardConfig.checkedCoreClockMHz(coreClockMHz)) ++
    new WithFpgaPlatformConfig(FpgaPlatformSettings(
      board = FpgaBoard.U55c,
      clockMHz = U55cBoardConfig.checkedCoreClockMHz(coreClockMHz),
      platformClockMHz = U55cBoardConfig.PlatformDataClockMHz,
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
  coreClockMHz = 300,
  ipAttachment = U55cXilinxIpAttachment(
    OperatorIpTimingConfig.Default.copy(
      multiply = OperatorIpTimingConfig.Default.multiply.copy(latency = 6)
    ),
    dividerNonBlocking = true
  )
)

/** Explicitly retains v11 interactive SDB support in the 300 MHz terminal. */
class U55c300MHzSdbBoardConfig extends CDEConfig(
  new WithFpgaRuntimeSdbConfig(FpgaRuntimeSdbConfig.Enabled) ++
    new U55c300MHzBoardConfig
)

/** U55C 300 MHz policy plus the v13 batch performance-monitor ABI. */
class U55c300MHzPerformanceMonitorBoardConfig extends CDEConfig(
  new WithFpgaPerformanceMonitorConfig(FpgaPerformanceMonitorConfig.U55cBatch) ++
    new WithFpgaRuntimeSdbConfig(FpgaRuntimeSdbConfig.Disabled) ++
    new U55c300MHzBoardConfig
)

/** U55C performance-monitor policy for a slower, exact core clock.  The
  * kernel interfaces remain on the stock 300 MHz DATA_CLK; board RTL creates
  * this core clock and crosses every AXI channel through an async FIFO.
  */
class U55cPerformanceMonitorBoardConfig(coreClockMHz: Int) extends CDEConfig(
  new WithFpgaPerformanceMonitorConfig(FpgaPerformanceMonitorConfig.U55cBatch) ++
    new WithFpgaRuntimeSdbConfig(FpgaRuntimeSdbConfig.Disabled) ++
    new U55cBoardConfig(coreClockMHz = coreClockMHz, ipAttachment = U55cXilinxIpAttachment(
      OperatorIpTimingConfig.Default.copy(
        multiply = OperatorIpTimingConfig.Default.multiply.copy(latency = 6)
      ),
      dividerNonBlocking = true
    ))
)
