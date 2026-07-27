package npc.ip

import circt.stage.ChiselStage
import chisel3._
import npc.ip.peripheral.{SpiBitRevIp, SpiControllerIp, SpiFlashIp, Uart16550Ip}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private final class PeripheralIpHarness extends Module {
  val io = IO(Output(Bool()))
  val uart = Module(new Uart16550Ip)
  uart.io.clock := clock
  uart.io.reset := reset
  uart.io.in.paddr := 0.U
  uart.io.in.psel := false.B
  uart.io.in.penable := false.B
  uart.io.in.pprot := 0.U
  uart.io.in.pwrite := false.B
  uart.io.in.pwdata := 0.U
  uart.io.in.pstrb := 0.U
  uart.io.uart.rx := false.B

  val spi = Module(new SpiControllerIp)
  spi.io.clock := clock
  spi.io.reset := reset
  spi.io.in.paddr := 0.U
  spi.io.in.psel := false.B
  spi.io.in.penable := false.B
  spi.io.in.pprot := 0.U
  spi.io.in.pwrite := false.B
  spi.io.in.pwdata := 0.U
  spi.io.in.pstrb := 0.U
  spi.io.spi.miso := false.B

  val flash = Module(new SpiFlashIp)
  flash.io.sck := false.B
  flash.io.ss := true.B
  flash.io.mosi := false.B

  val bitrev = Module(new SpiBitRevIp)
  bitrev.io.sck := false.B
  bitrev.io.ss := true.B
  bitrev.io.mosi := false.B

  io := spi.io.spi_irq_out
}

final class PeripheralIpContractSpec extends AnyFlatSpec with Matchers {
  "UART/SPI IP" should "保留稳定 module 名称并独立 elaboration" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new PeripheralIpHarness)
    chirrtl should include("uart_top_apb")
    chirrtl should include("spi_top_apb")
    chirrtl should include("flash")
    chirrtl should include("bitrev")
  }
}
