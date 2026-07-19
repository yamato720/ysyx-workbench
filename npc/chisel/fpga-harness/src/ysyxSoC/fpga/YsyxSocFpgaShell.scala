package ysyx.fpga

import chisel3._
import org.chipsalliance.cde.config.Parameters
import _root_.scpu.fpga.{FpgaBoard, FpgaConfigParameters, FpgaSystemIO}
import _root_.ysyx.ChipLinkParam

/** Board shell boundary. Board-specific AXI pin adaptation remains in board RTL. */
abstract class YsyxSocFpgaShell(
  board: FpgaBoard
)(implicit parameters: Parameters) extends Module {
  private val platform = FpgaConfigParameters.platform
  require(platform.board == board,
    s"elaboration selected ${platform.board.name}, but instantiated ${board.name} shell")
  require(FpgaConfigParameters.board.contains(board),
    s"CDE configuration selected ${FpgaConfigParameters.board.map(_.name).getOrElse("no board")}, but instantiated ${board.name} shell")
  override def desiredName: String = "NpcFpgaTop"

  val io = IO(new FpgaSystemIO(32, 32, ChipLinkParam.idBits))
  private val system = Module(new YsyxSocFpgaSystem)
  FpgaSystemIO.connect(io, system.io)
}
