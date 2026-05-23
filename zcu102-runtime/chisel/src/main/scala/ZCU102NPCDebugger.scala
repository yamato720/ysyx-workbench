package zcu102

import chisel3._
import chisel3.util._

class AxiLite32Addr extends Bundle {
  val addr = UInt(32.W)
  val prot = UInt(3.W)
}

class AxiLite32WriteData extends Bundle {
  val data = UInt(32.W)
  val strb = UInt(4.W)
}

class AxiLite32WriteResp extends Bundle {
  val resp = UInt(2.W)
}

class AxiLite32ReadData extends Bundle {
  val data = UInt(32.W)
  val resp = UInt(2.W)
}

class AxiLite32SlaveIO extends Bundle {
  val aw = Flipped(Decoupled(new AxiLite32Addr))
  val w = Flipped(Decoupled(new AxiLite32WriteData))
  val b = Decoupled(new AxiLite32WriteResp)
  val ar = Flipped(Decoupled(new AxiLite32Addr))
  val r = Decoupled(new AxiLite32ReadData)
}

class NPCDebugControlIO extends Bundle {
  val run = Output(Bool())
  val reset = Output(Bool())
  val haltReq = Output(Bool())
  val singleStep = Output(Bool())
  val traceEnable = Output(Bool())
  val bootPc = Output(UInt(32.W))
}

class NPCDebugStatusIO extends Bundle {
  val running = Input(Bool())
  val halted = Input(Bool())
  val busy = Input(Bool())
  val trapValid = Input(Bool())
  val trapCause = Input(UInt(32.W))
}

class NPCCommitIO extends Bundle {
  val valid = Input(Bool())
  val pc = Input(UInt(64.W))
  val inst = Input(UInt(32.W))
  val rd = Input(UInt(5.W))
  val rdWen = Input(Bool())
  val rdWdata = Input(UInt(64.W))
}

class NPCSimpleMmioIO extends Bundle {
  val putchValid = Input(Bool())
  val putchData = Input(UInt(8.W))
  val haltValid = Input(Bool())
  val exitValid = Input(Bool())
  val exitCode = Input(UInt(32.W))
}

class ZCU102NPCDebugger extends Module {
  val io = IO(new Bundle {
    val axil = new AxiLite32SlaveIO
    val ctrl = new NPCDebugControlIO
    val status = new NPCDebugStatusIO
    val commit = new NPCCommitIO
    val mmio = new NPCSimpleMmioIO
    val clearTracePulse = Output(Bool())
    val clearPutchPulse = Output(Bool())
    val irqToPs = Output(Bool())
  })

  private object RegMap {
    val Control = 0x000
    val Status = 0x004
    val BootPc = 0x008
    val ExitCode = 0x00c
    val CycleLow = 0x010
    val CycleHigh = 0x014
    val InstretLow = 0x018
    val InstretHigh = 0x01c
    val LastCommitPcLow = 0x020
    val LastCommitPcHigh = 0x024
    val LastCommitInst = 0x028
    val LastCommitRd = 0x02c
    val LastCommitWdataLow = 0x030
    val LastCommitWdataHigh = 0x034
    val TrapCause = 0x038
    val TraceHead = 0x040
    val TraceTail = 0x044
    val TraceCount = 0x048
    val TraceBase = 0x04c
    val TraceSize = 0x050
    val PutchData = 0x060
    val PutchStatus = 0x064
  }

  private def wmask(oldValue: UInt, newValue: UInt, strb: UInt): UInt = {
    val bytes = Wire(Vec(4, UInt(8.W)))
    for (i <- 0 until 4) {
      bytes(i) := Mux(strb(i), newValue(8 * i + 7, 8 * i), oldValue(8 * i + 7, 8 * i))
    }
    bytes.asUInt
  }

  val control = RegInit("h0000_0002".U(32.W))
  val bootPc = RegInit("h8000_0000".U(32.W))
  val exitCode = RegInit(0.U(32.W))
  val cycle = RegInit(0.U(64.W))
  val instret = RegInit(0.U(64.W))
  val lastCommitPc = RegInit(0.U(64.W))
  val lastCommitInst = RegInit(0.U(32.W))
  val lastCommitRd = RegInit(0.U(5.W))
  val lastCommitRdWen = RegInit(false.B)
  val lastCommitRdWdata = RegInit(0.U(64.W))
  val trapCause = RegInit(0.U(32.W))
  val traceHead = RegInit(0.U(32.W))
  val traceTail = RegInit(0.U(32.W))
  val traceCount = RegInit(0.U(32.W))
  val traceBase = RegInit(0.U(32.W))
  val traceSize = RegInit(0.U(32.W))
  val putchData = RegInit(0.U(8.W))
  val putchValid = RegInit(false.B)
  val haltedLatch = RegInit(false.B)
  val trapLatch = RegInit(false.B)

  io.clearTracePulse := false.B
  io.clearPutchPulse := false.B

  cycle := cycle + 1.U
  when(io.commit.valid) {
    instret := instret + 1.U
    lastCommitPc := io.commit.pc
    lastCommitInst := io.commit.inst
    lastCommitRd := io.commit.rd
    lastCommitRdWen := io.commit.rdWen
    lastCommitRdWdata := io.commit.rdWdata
  }

  when(io.status.trapValid) {
    trapLatch := true.B
    trapCause := io.status.trapCause
  }

  when(io.status.halted || io.mmio.haltValid) {
    haltedLatch := true.B
  }

  when(io.mmio.exitValid) {
    exitCode := io.mmio.exitCode
  }

  when(io.mmio.putchValid) {
    putchData := io.mmio.putchData
    putchValid := true.B
  }

  io.ctrl.run := control(0)
  io.ctrl.reset := control(1)
  io.ctrl.haltReq := control(2)
  io.ctrl.singleStep := control(3)
  io.ctrl.traceEnable := control(4)
  io.ctrl.bootPc := bootPc
  io.irqToPs := haltedLatch || trapLatch || putchValid

  val writeReady = !io.axil.b.valid
  val readReady = !io.axil.r.valid
  io.axil.aw.ready := writeReady
  io.axil.w.ready := writeReady
  io.axil.ar.ready := readReady
  io.axil.b.bits.resp := 0.U
  io.axil.r.bits.resp := 0.U

  val writeFire = io.axil.aw.fire && io.axil.w.fire
  val readFire = io.axil.ar.fire
  val bValid = RegInit(false.B)
  val rValid = RegInit(false.B)
  val rData = RegInit(0.U(32.W))

  io.axil.b.valid := bValid
  io.axil.r.valid := rValid
  io.axil.r.bits.data := rData

  val statusValue = Cat(
    26.U(26.W),
    putchValid,
    false.B,
    io.status.trapValid || trapLatch,
    io.status.busy,
    io.status.halted || haltedLatch,
    io.status.running
  )

  def readReg(addr: UInt): UInt = {
    MuxLookup(addr(11, 0), 0.U(32.W))(Seq(
      RegMap.Control.U -> control,
      RegMap.Status.U -> statusValue,
      RegMap.BootPc.U -> bootPc,
      RegMap.ExitCode.U -> exitCode,
      RegMap.CycleLow.U -> cycle(31, 0),
      RegMap.CycleHigh.U -> cycle(63, 32),
      RegMap.InstretLow.U -> instret(31, 0),
      RegMap.InstretHigh.U -> instret(63, 32),
      RegMap.LastCommitPcLow.U -> lastCommitPc(31, 0),
      RegMap.LastCommitPcHigh.U -> lastCommitPc(63, 32),
      RegMap.LastCommitInst.U -> lastCommitInst,
      RegMap.LastCommitRd.U -> Cat(0.U(26.W), lastCommitRdWen, lastCommitRd),
      RegMap.LastCommitWdataLow.U -> lastCommitRdWdata(31, 0),
      RegMap.LastCommitWdataHigh.U -> lastCommitRdWdata(63, 32),
      RegMap.TrapCause.U -> trapCause,
      RegMap.TraceHead.U -> traceHead,
      RegMap.TraceTail.U -> traceTail,
      RegMap.TraceCount.U -> traceCount,
      RegMap.TraceBase.U -> traceBase,
      RegMap.TraceSize.U -> traceSize,
      RegMap.PutchData.U -> Cat(0.U(24.W), putchData),
      RegMap.PutchStatus.U -> Cat(0.U(31.W), putchValid)
    ))
  }

  when(writeFire) {
    switch(io.axil.aw.bits.addr(11, 0)) {
      is(RegMap.Control.U) {
        control := wmask(control, io.axil.w.bits.data, io.axil.w.bits.strb)
        when(io.axil.w.bits.data(5)) {
          traceHead := 0.U
          traceTail := 0.U
          traceCount := 0.U
          io.clearTracePulse := true.B
        }
        when(io.axil.w.bits.data(6)) {
          putchValid := false.B
          io.clearPutchPulse := true.B
        }
        when(io.axil.w.bits.data(7)) {
          haltedLatch := false.B
          trapLatch := false.B
        }
      }
      is(RegMap.BootPc.U) { bootPc := wmask(bootPc, io.axil.w.bits.data, io.axil.w.bits.strb) }
      is(RegMap.ExitCode.U) { exitCode := wmask(exitCode, io.axil.w.bits.data, io.axil.w.bits.strb) }
      is(RegMap.TraceHead.U) { traceHead := wmask(traceHead, io.axil.w.bits.data, io.axil.w.bits.strb) }
      is(RegMap.TraceTail.U) { traceTail := wmask(traceTail, io.axil.w.bits.data, io.axil.w.bits.strb) }
      is(RegMap.TraceCount.U) { traceCount := wmask(traceCount, io.axil.w.bits.data, io.axil.w.bits.strb) }
      is(RegMap.TraceBase.U) { traceBase := wmask(traceBase, io.axil.w.bits.data, io.axil.w.bits.strb) }
      is(RegMap.TraceSize.U) { traceSize := wmask(traceSize, io.axil.w.bits.data, io.axil.w.bits.strb) }
    }
    bValid := true.B
  }.elsewhen(io.axil.b.fire) {
    bValid := false.B
  }

  when(readFire) {
    rData := readReg(io.axil.ar.bits.addr)
    rValid := true.B
    when(io.axil.ar.bits.addr(11, 0) === RegMap.PutchData.U) {
      putchValid := false.B
    }
  }.elsewhen(io.axil.r.fire) {
    rValid := false.B
  }
}

object ElaborateZCU102NPCDebugger extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(
    new ZCU102NPCDebugger,
    Array("--target-dir", "../zcu102-runtime/generated", "--output-file", "ZCU102NPCDebugger")
  )
}
