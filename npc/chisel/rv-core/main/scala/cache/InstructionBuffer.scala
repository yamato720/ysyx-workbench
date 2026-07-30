package npc

import chisel3._
import chisel3.util._
import npc.protocol.FetchDecodePayload

/** Small in-order fetch queue. Redirects synchronously discard all speculative entries. */
class InstructionBuffer(entries: Int, cfg: ISAConfig) extends Module {
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
  io.out.valid := count =/= 0.U
  io.out.bits := storage(readPointer)

  def next(pointer: UInt): UInt =
    if (entries == 1) 0.U else Mux(pointer === (entries - 1).U, 0.U, pointer + 1.U)

  when(io.flush) {
    readPointer := 0.U
    writePointer := 0.U
    count := 0.U
  }.elsewhen(io.dropYounger) {
    // FENCE.I remains at the head until maintenance finishes, but every
    // younger instruction may have been fetched from stale I$ contents.
    writePointer := next(readPointer)
    count := Mux(count === 0.U, 0.U, 1.U)
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
