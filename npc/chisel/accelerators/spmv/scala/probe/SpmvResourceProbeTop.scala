package accelerators.spmv.probe

import chisel3._
import accelerators.spmv.SpmvAcceleratorConfig
import npc.ip.axi.Axi4ReadMasterIO

/** 并行实例化全部 PC，并把每路 checksum 纳入可观察的聚合结果。 */
final class SpmvResourceProbeTop(config: SpmvAcceleratorConfig) extends Module {
  override def desiredName: String = "SpmvResourceProbeTop"

  val io = IO(new Bundle {
    val start = Input(Bool())
    val baseAddresses = Input(Vec(config.hbmPcCount, UInt(config.axiAddrWidth.W)))
    val axi = Vec(config.hbmPcCount,
      new Axi4ReadMasterIO(config.axiAddrWidth, config.axiDataWidth, config.axiIdWidth))
    val aggregateChecksum = Output(UInt(config.elementWidth.W))
    val doneMask = Output(UInt(config.hbmPcCount.W))
    val errorMask = Output(UInt(config.hbmPcCount.W))
  })

  val lanes = Seq.tabulate(config.hbmPcCount) { index =>
    val lane = Module(new SpmvResourceProbeLane(config))
    lane.suggestName(f"pc${index}%02d")
    lane.io.start := io.start
    lane.io.baseAddress := io.baseAddresses(index)
    io.axi(index) <> lane.io.axi
    lane
  }
  io.aggregateChecksum := lanes.map(_.io.checksum).reduce(_ ^ _)
  io.doneMask := VecInit(lanes.map(_.io.done)).asUInt
  io.errorMask := VecInit(lanes.map(_.io.error)).asUInt
}
