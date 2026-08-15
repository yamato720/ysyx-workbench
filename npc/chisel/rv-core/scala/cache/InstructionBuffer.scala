package npc

import chisel3._
import chisel3.util._
import npc.protocol.FetchDecodePayload

/** 小型顺序取指队列；redirect 在时钟边沿丢弃全部推测项。 */
class InstructionBuffer(entries: Int, cfg: ISAConfig, flowThrough: Boolean = false) extends Module {
  require(PowerOfTwo(entries), s"instruction buffer entries must be a power of two, got $entries")
  private val pointerWidth = math.max(1, log2Ceil(entries))

  val io = IO(new Bundle {
    val flush = Input(Bool())
    val dropYounger = Input(Bool())
    val in = Flipped(Decoupled(new FetchDecodePayload(cfg)))
    val out = Decoupled(new FetchDecodePayload(cfg))
  })

  val storage = Reg(Vec(entries, new FetchDecodePayload(cfg)))
  val readPointer = RegInit(0.U(pointerWidth.W))
  val writePointer = RegInit(0.U(pointerWidth.W))
  val count = RegInit(0.U(log2Ceil(entries + 1).W))

  io.in.ready := count =/= entries.U
  // 流水模式下，空队列可把本周期刚到达的取指结果直接交给 ID；非流水模式仍保持一拍寄存行为。
  // FENCE.I 维护期间只允许已保存的队首继续等待或被释放；本周期新到的响应属于
  // 失效前的年轻取指，不能通过 flow-through 绕过丢弃逻辑。
  io.out.valid := count =/= 0.U || (flowThrough.B && io.in.valid && !io.dropYounger)
  io.out.bits := Mux(count === 0.U && flowThrough.B, io.in.bits, storage(readPointer))

  def next(pointer: UInt): UInt =
    if (entries == 1) 0.U else Mux(pointer === (entries - 1).U, 0.U, pointer + 1.U)

  when(io.flush) {
    readPointer := 0.U
    writePointer := 0.U
    count := 0.U
  }.elsewhen(io.dropYounger) {
    // FENCE.I 维护期间丢弃队首之后的预取项。若维护控制器在本周期释放队首且
    // dispatch 完成握手，必须同时推进读指针；否则 fence 会在下一拍被重新识别。
    when(io.out.fire) {
      readPointer := next(readPointer)
      writePointer := next(readPointer)
      count := 0.U
    }.otherwise {
      writePointer := next(readPointer)
      count := Mux(count === 0.U, 0.U, 1.U)
    }
  }.otherwise {
    when(io.in.fire) {
      storage(writePointer) := io.in.bits
      writePointer := next(writePointer)
    }
    when(io.out.fire) { readPointer := next(readPointer) }
    switch(Cat(io.in.fire, io.out.fire)) {
      is("b10".U) { count := count + 1.U }
      is("b01".U) { count := count - 1.U }
    }
  }
}
