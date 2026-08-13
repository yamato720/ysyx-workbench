package npc.ip.axi

import chisel3._
import chisel3.util._

/** AXI4 burst 类型。 */
object Axi4Burst {
  val Fixed = 0.U(2.W)
  val Incr = 1.U(2.W)
  val Wrap = 2.U(2.W)
}

/** AXI4 AW/AR 通道共用的地址载荷。 */
final class Axi4Address(val addrWidth: Int, val idWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val addr = UInt(addrWidth.W)
  val len = UInt(8.W)
  val size = UInt(3.W)
  val burst = UInt(2.W)
  val lock = UInt(1.W)
  val cache = UInt(4.W)
  val prot = UInt(3.W)
  val qos = UInt(4.W)
}

/** AXI4 W 通道载荷。 */
final class Axi4WriteData(val dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val strb = UInt((dataWidth / 8).W)
  val last = Bool()
}

/** AXI4 B 通道载荷。 */
final class Axi4WriteResponse(val idWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val resp = UInt(2.W)
}

/** AXI4 R 通道载荷。 */
final class Axi4ReadData(val dataWidth: Int, val idWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val data = UInt(dataWidth.W)
  val resp = UInt(2.W)
  val last = Bool()
}

private[axi] object Axi4ContractChecks {
  def apply(addrWidth: Int, dataWidth: Int, idWidth: Int): Unit = {
    require(addrWidth > 0, s"AXI4 地址位宽必须为正数，实际为 $addrWidth")
    require(dataWidth >= 8 && (dataWidth & (dataWidth - 1)) == 0,
      s"AXI4 数据位宽必须是至少 8 bit 的二次幂，实际为 $dataWidth")
    require(idWidth > 0, s"AXI4 ID 位宽必须为正数，实际为 $idWidth")
  }
}

/** AXI4 只读主设备端口，只暴露 AR/R 通道。 */
final class Axi4ReadMasterIO(
  val addrWidth: Int,
  val dataWidth: Int,
  val idWidth: Int
) extends Bundle {
  Axi4ContractChecks(addrWidth, dataWidth, idWidth)

  val ar = Irrevocable(new Axi4Address(addrWidth, idWidth))
  val r = Flipped(Irrevocable(new Axi4ReadData(dataWidth, idWidth)))
}

/** AXI4 只写主设备端口，只暴露 AW/W/B 通道。 */
final class Axi4WriteMasterIO(
  val addrWidth: Int,
  val dataWidth: Int,
  val idWidth: Int
) extends Bundle {
  Axi4ContractChecks(addrWidth, dataWidth, idWidth)

  val aw = Irrevocable(new Axi4Address(addrWidth, idWidth))
  val w = Irrevocable(new Axi4WriteData(dataWidth))
  val b = Flipped(Irrevocable(new Axi4WriteResponse(idWidth)))
}

/** AXI4 完整读写主设备端口，暴露 AW/W/B/AR/R 五个通道。 */
final class Axi4ReadWriteMasterIO(
  val addrWidth: Int,
  val dataWidth: Int,
  val idWidth: Int
) extends Bundle {
  Axi4ContractChecks(addrWidth, dataWidth, idWidth)

  val aw = Irrevocable(new Axi4Address(addrWidth, idWidth))
  val w = Irrevocable(new Axi4WriteData(dataWidth))
  val b = Flipped(Irrevocable(new Axi4WriteResponse(idWidth)))
  val ar = Irrevocable(new Axi4Address(addrWidth, idWidth))
  val r = Flipped(Irrevocable(new Axi4ReadData(dataWidth, idWidth)))
}
