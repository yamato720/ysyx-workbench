package npc.ip.peripheral

import chisel3._
import chisel3.util.HasBlackBoxResource
import npc.ip.bus.{ApbSlavePort, SpiPins}

/** SPI 控制器功能模型；flash 地址范围由 SoC 地址映射保留。 */
final class SpiControllerIp extends BlackBox with HasBlackBoxResource {
  override def desiredName: String = "spi_top_apb"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in = new ApbSlavePort()
    val spi = new SpiPins(8)
    val spi_irq_out = Output(Bool())
  })

  addResource("/npc/ip/peripheral/spi/rtl/spi_top_apb.v")
  addResource("/npc/ip/peripheral/spi/rtl/spi_top.v")
  addResource("/npc/ip/peripheral/spi/rtl/spi_shift.v")
  addResource("/npc/ip/peripheral/spi/rtl/spi_defines.v")
  addResource("/npc/ip/peripheral/spi/rtl/spi_clgen.v")
}

/** SPI flash 功能模型，保留原有 Verilog module 名称。 */
final class SpiFlashIp extends BlackBox with HasBlackBoxResource {
  override def desiredName: String = "flash"
  val io = IO(Flipped(new SpiPins(1)))
  addResource("/npc/ip/peripheral/flash/flash.v")
}

/** SPI bit-reversal 从设备，保留原有 Verilog module 名称。 */
final class SpiBitRevIp extends BlackBox with HasBlackBoxResource {
  override def desiredName: String = "bitrev"
  val io = IO(Flipped(new SpiPins(1)))
  addResource("/npc/ip/peripheral/bitrev/bitrev.v")
}
