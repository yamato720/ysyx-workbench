package npc

import chisel3._
import chisel3.util._

/** Set-local replacement state. Invalid-way preference remains in CacheController. */
class CacheReplacementUnit(sets: Int, ways: Int, policy: CacheReplacement) extends Module {
  require(PowerOfTwo(sets) && PowerOfTwo(ways))

  private val setWidth = math.max(1, log2Ceil(sets))
  private val wayWidth = math.max(1, log2Ceil(ways))
  val io = IO(new Bundle {
    val querySet = Input(UInt(setWidth.W))
    val victimWay = Output(UInt(wayWidth.W))
    val accessValid = Input(Bool())
    val replaceValid = Input(Bool())
    val accessSet = Input(UInt(setWidth.W))
    val accessWay = Input(UInt(wayWidth.W))
  })

  if (ways == 1) {
    io.victimWay := 0.U
  } else policy match {
    case CacheReplacement.LRU =>
      // Rank 0 is MRU and rank ways-1 is LRU. Keeping the ranks unique avoids
      // the ties produced by a saturating "increment every other way" scheme.
      val ages = RegInit(VecInit(Seq.fill(sets)(VecInit(
        (0 until ways).map(_.U(wayWidth.W))))))
      val queried = if (sets == 1) ages(0) else ages(io.querySet)
      val oldest = queried.reduce((left, right) => Mux(left > right, left, right))
      io.victimWay := PriorityEncoder(VecInit(queried.map(_ === oldest)).asUInt)
      when(io.accessValid) {
        val accessedAge = if (sets == 1) ages(0)(io.accessWay) else ages(io.accessSet)(io.accessWay)
        for (way <- 0 until ways) {
          when(io.accessWay === way.U) {
            if (sets == 1) ages(0)(way) := 0.U else ages(io.accessSet)(way) := 0.U
          }.elsewhen((if (sets == 1) ages(0)(way) else ages(io.accessSet)(way)) < accessedAge) {
            if (sets == 1) ages(0)(way) := ages(0)(way) + 1.U
            else ages(io.accessSet)(way) := ages(io.accessSet)(way) + 1.U
          }
        }
      }

    case CacheReplacement.FIFO =>
      val pointers = RegInit(VecInit(Seq.fill(sets)(0.U(wayWidth.W))))
      io.victimWay := (if (sets == 1) pointers(0) else pointers(io.querySet))
      when(io.replaceValid) {
        val next = Mux(io.accessWay === (ways - 1).U, 0.U, io.accessWay + 1.U)
        if (sets == 1) pointers(0) := next else pointers(io.accessSet) := next
      }

    case CacheReplacement.Random =>
      val lfsr = RegInit("h1ace".U(16.W))
      io.victimWay := lfsr(wayWidth - 1, 0)
      when(io.replaceValid) {
        lfsr := Cat(lfsr(14, 0), lfsr(15) ^ lfsr(13) ^ lfsr(12) ^ lfsr(10))
      }

    case CacheReplacement.TreePLRU =>
      val tree = RegInit(VecInit(Seq.fill(sets)(0.U((ways - 1).W))))
      if (ways == 2) {
        io.victimWay := (if (sets == 1) tree(0)(0) else tree(io.querySet)(0))
        when(io.accessValid) {
          if (sets == 1) tree(0) := !io.accessWay(0) else tree(io.accessSet) := !io.accessWay(0)
        }
      } else {
        val queried = if (sets == 1) tree(0) else tree(io.querySet)
        val nodeWidth = math.max(1, log2Ceil(ways - 1))
        var node = 0.U(nodeWidth.W)
        var victim = 0.U(wayWidth.W)
        for (level <- 0 until log2Ceil(ways)) {
          val direction = queried(node)
          victim = victim | (direction.asUInt << (log2Ceil(ways) - level - 1))
          node = ((node << 1) + 1.U + direction.asUInt)(nodeWidth - 1, 0)
        }
        io.victimWay := victim
        when(io.accessValid) {
          val next = Wire(Vec(ways - 1, Bool()))
          next := (if (sets == 1) tree(0) else tree(io.accessSet)).asBools
          var updateNode = 0.U(nodeWidth.W)
          for (level <- 0 until log2Ceil(ways)) {
            val direction = io.accessWay(log2Ceil(ways) - level - 1)
            next(updateNode) := !direction
            updateNode = ((updateNode << 1) + 1.U + direction.asUInt)(nodeWidth - 1, 0)
          }
          if (sets == 1) tree(0) := next.asUInt else tree(io.accessSet) := next.asUInt
        }
      }
  }

  if (ways == 1) {
    dontTouch(io.accessValid)
    dontTouch(io.accessSet)
    dontTouch(io.accessWay)
    dontTouch(io.replaceValid)
  }
}
