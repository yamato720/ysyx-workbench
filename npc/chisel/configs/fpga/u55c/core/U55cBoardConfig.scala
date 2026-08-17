package fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.fpga.FpgaBoard
import _root_.fpga.FpgaPlatformSettings
import _root_.fpga.{FpgaPerformanceMonitorConfig, FpgaRuntimeSdbConfig, WithFpgaBoardConfig, WithFpgaClockMHzConfig, WithFpgaPerformanceMonitorConfig, WithFpgaPlatformConfig, WithFpgaRuntimeSdbConfig}
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

/** 连接 HBM 的 RTL kernel 使用的标准 U55C 平台合同。 */
object U55cBoardConfig {
  /** 驱动 Vitis RTL-kernel 接口的标准 shell 时钟。 */
  val PlatformDataClockMHz = 300
  val SupportedCoreClockMHz: Set[Int] = Set(100, 125, 150, 200, 225, 250, 300)

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

/** U55C 100 MHz 的高性能整数板卡策略。
  *
  * 核心保留分段乘法器和无回压除法器；本次上板只保留普通运行时 ABI，关闭性能监测
  * trace 与 SDB halt/step 调试面，避免额外的观测通路参与实现压力评估。
  */
class U55c100MHzBoardConfig extends CDEConfig(
  new WithFpgaPerformanceMonitorConfig(FpgaPerformanceMonitorConfig.Disabled) ++
    new WithFpgaRuntimeSdbConfig(FpgaRuntimeSdbConfig.Disabled) ++
    new U55cBoardConfig(
      coreClockMHz = 100,
      ipAttachment = U55cXilinxIpAttachment(
        OperatorIpTimingConfig.Default.copy(
          multiply = OperatorIpTimingConfig.Default.multiply.copy(latency = 6)
        ),
        dividerNonBlocking = true
      )
    )
)

/** U55C 225 MHz 的高性能整数板卡策略，关闭性能监测与 FPGA 运行时调试。 */
class U55c225MHzBoardConfig extends CDEConfig(
  new WithFpgaPerformanceMonitorConfig(FpgaPerformanceMonitorConfig.Disabled) ++
    new WithFpgaRuntimeSdbConfig(FpgaRuntimeSdbConfig.Disabled) ++
    new U55cBoardConfig(
      coreClockMHz = 225,
      ipAttachment = U55cXilinxIpAttachment(
        OperatorIpTimingConfig.Default.copy(
          multiply = OperatorIpTimingConfig.Default.multiply.copy(latency = 6)
        ),
        dividerNonBlocking = true
      )
    )
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

/** 在 300 MHz 终端中显式保留 v11 交互式 SDB。 */
class U55c300MHzSdbBoardConfig extends CDEConfig(
  new WithFpgaRuntimeSdbConfig(FpgaRuntimeSdbConfig.Enabled) ++
    new U55c300MHzBoardConfig
)

/** U55C 300 MHz 策略与 v13 batch performance-monitor ABI。 */
class U55c300MHzPerformanceMonitorBoardConfig extends CDEConfig(
  new WithFpgaPerformanceMonitorConfig(FpgaPerformanceMonitorConfig.U55cBatch) ++
    new WithFpgaRuntimeSdbConfig(FpgaRuntimeSdbConfig.Disabled) ++
    new U55c300MHzBoardConfig
)

/** 使用较低精确核心频率的 U55C performance-monitor 策略。
  *
  * kernel 接口保持标准 300 MHz `DATA_CLK`；板卡 RTL 生成核心时钟，并让每条 AXI
  * 通道通过异步 FIFO 跨时钟域。
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
