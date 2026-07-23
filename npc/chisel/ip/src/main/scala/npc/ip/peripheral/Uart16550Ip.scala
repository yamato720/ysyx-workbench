package npc.ip.peripheral

import chisel3._
import chisel3.util.HasBlackBoxResource
import npc.ip.bus.{ApbSlavePort, UartPins}

/** UART16550 功能模型；地址译码和 APB node 由 SoC 集成层负责。 */
final class Uart16550Ip extends BlackBox with HasBlackBoxResource {
  override def desiredName: String = "uart_top_apb"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in = new ApbSlavePort()
    val uart = new UartPins
  })

  addResource("/npc/ip/peripheral/uart16550/rtl/uart_top_apb.v")
  addResource("/npc/ip/peripheral/uart16550/rtl/uart_receiver.v")
  addResource("/npc/ip/peripheral/uart16550/rtl/uart_regs.v")
  addResource("/npc/ip/peripheral/uart16550/rtl/uart_defines.v")
  addResource("/npc/ip/peripheral/uart16550/rtl/uart_transmitter.v")
  addResource("/npc/ip/peripheral/uart16550/rtl/uart_rfifo.v")
  addResource("/npc/ip/peripheral/uart16550/rtl/uart_sync_flops.v")
  addResource("/npc/ip/peripheral/uart16550/rtl/uart_tfifo.v")
  addResource("/npc/ip/peripheral/uart16550/rtl/raminfr.v")
}
