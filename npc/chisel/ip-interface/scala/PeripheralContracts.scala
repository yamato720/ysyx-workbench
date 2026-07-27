package npc.ip.bus

import chisel3._

/** 与 Rocket/Diplomacy 无关的 APB 从端口契约。 */
final class ApbSlavePort(val addrWidth: Int = 32, val dataWidth: Int = 32) extends Bundle {
  require(dataWidth % 8 == 0)
  val paddr = Input(UInt(addrWidth.W))
  val psel = Input(Bool())
  val penable = Input(Bool())
  val pprot = Input(UInt(3.W))
  val pwrite = Input(Bool())
  val pwdata = Input(UInt(dataWidth.W))
  val pstrb = Input(UInt((dataWidth / 8).W))
  val pready = Output(Bool())
  val prdata = Output(UInt(dataWidth.W))
  val pslverr = Output(Bool())

}

/** UART16550 的稳定板级引脚。 */
final class UartPins extends Bundle {
  val rx = Input(Bool())
  val tx = Output(Bool())
}

/** SPI master 的稳定板级引脚。 */
final class SpiPins(val ssWidth: Int = 8) extends Bundle {
  require(ssWidth > 0)
  val sck = Output(Bool())
  val ss = Output(UInt(ssWidth.W))
  val mosi = Output(Bool())
  val miso = Input(Bool())

}
