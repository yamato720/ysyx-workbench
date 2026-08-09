package npc.ip.memory

import chisel3._
import chisel3.util._

/** HBM 读地址载荷；字段保持 AXI4 read address 的稳定形状。 */
final class HbmReadAddress(val addrWidth: Int, val idWidth: Int) extends Bundle {
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

/** HBM 读数据载荷；支持多拍 burst 的末拍标记和 AXI 响应码。 */
final class HbmReadData(val dataWidth: Int, val idWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val data = UInt(dataWidth.W)
  val resp = UInt(2.W)
  val last = Bool()
}

/** 厂商无关的 HBM 只读主设备端口。
  *
  * 这里只保留 reader 需要的 AR/R 通道；平台 adapter 负责把它连接到完整
  * AXI4-Full 或厂商 HBM shell。AR 使用 Irrevocable，R 的方向从主设备视角
  * 反转，确保模块之间只依赖这份公共契约。
  */
final class HbmReadMasterIO(
  val addrWidth: Int,
  val dataWidth: Int,
  val idWidth: Int
) extends Bundle {
  require(addrWidth > 0, s"HBM 地址位宽必须为正数，实际为 $addrWidth")
  require(dataWidth >= 8 && dataWidth % 8 == 0,
    s"HBM 数据位宽必须按字节对齐，实际为 $dataWidth")
  require(idWidth > 0, s"HBM ID 位宽必须为正数，实际为 $idWidth")

  val ar = Irrevocable(new HbmReadAddress(addrWidth, idWidth))
  val r = Flipped(Irrevocable(new HbmReadData(dataWidth, idWidth)))
}
