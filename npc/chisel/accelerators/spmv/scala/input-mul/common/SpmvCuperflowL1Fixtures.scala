package accelerators.spmv.inputmul.common

/**
  * Cuperflow L1 的稳定合成 RowPartial fixture。
  *
  * V0 C++ package 用同名 CSR fixture 覆盖编码、descriptor 和 X payload；本包则给
  * V1/V2 Chisel test 直接提供 L1 输入侧所需的 batch/wave/row/chunk 前置条件，避免
  * 为了测试独立 L1 而错误复用旧 v4 slot 行号。
  */
object SpmvCuperflowL1Fixtures {
  final case class RowPartial(
    batch: Int,
    wave: Int,
    localRow: Int,
    pc: Int,
    chunkWidth: Int,
    rowLast: Boolean,
    contributorMask: Int,
    segmentIds: Seq[Int] = Seq(0)
  ) {
    require(batch >= 0 && wave >= 0)
    require(localRow >= 0 && localRow < (1 << SpmvCuperDecode.rowBits))
    require(pc >= 0 && pc < 16)
    require(chunkWidth == 2 || chunkWidth == 4 || chunkWidth == 8)
    require(contributorMask >= 0 && contributorMask <= 0xffff)
    require(segmentIds.nonEmpty && segmentIds.forall(segment => segment >= 0 && segment < 8))
  }

  final case class Fixture(
    name: String,
    partials: Seq[RowPartial],
    emptyBatch: Boolean = false,
    shortFinalBatch: Boolean = false
  ) {
    require(name.nonEmpty)
    require(!emptyBatch || partials.isEmpty)
  }

  val full8: Fixture = Fixture("full8", Seq(
    RowPartial(0, 0, 0, 0, 8, rowLast = true, contributorMask = 0x0001)
  ))
  val tail44: Fixture = Fixture("tail44", Seq(
    RowPartial(0, 0, 0, 0, 4, rowLast = true, contributorMask = 0x0001),
    RowPartial(0, 0, 1, 0, 4, rowLast = true, contributorMask = 0x0001)
  ))
  val tail2222: Fixture = Fixture("tail2222", (0 until 4).map { row =>
    RowPartial(0, 0, row, 0, 2, rowLast = true, contributorMask = 0x0001)
  })
  val pad3_1: Fixture = Fixture("pad3_1", Seq(
    RowPartial(0, 0, 0, 0, 4, rowLast = true, contributorMask = 0x0001),
    RowPartial(0, 0, 1, 0, 2, rowLast = true, contributorMask = 0x0001)
  ))
  val empty_pc_row: Fixture = Fixture("empty_pc_row", Seq(
    RowPartial(0, 0, 0, 0, 2, rowLast = true, contributorMask = 0x0001)
  ))
  val empty_batch: Fixture = Fixture("empty_batch", Seq(), emptyBatch = true)
  val last_short_batch: Fixture = Fixture("last_short_batch", Seq(
    RowPartial(1, 0, 0, 0, 2, rowLast = true, contributorMask = 0x0001)
  ), shortFinalBatch = true)
  val same_local_row_next_batch: Fixture = Fixture("same_local_row_next_batch", Seq(
    RowPartial(0, 0, 0, 0, 2, rowLast = true, contributorMask = 0x0001),
    RowPartial(1, 0, 0, 0, 2, rowLast = true, contributorMask = 0x0001)
  ), shortFinalBatch = true)
  val multi_wave_same_y: Fixture = Fixture("multi_wave_same_y", Seq(
    RowPartial(0, 0, 0, 0, 2, rowLast = true, contributorMask = 0x0001),
    RowPartial(0, 1, 0, 0, 2, rowLast = true, contributorMask = 0x0001)
  ))
  val explicit_zero: Fixture = Fixture("explicit_zero", Seq(
    RowPartial(0, 0, 0, 0, 4, rowLast = true, contributorMask = 0x0001)
  ))
  val eight_x_segments: Fixture = Fixture("eight_x_segments", Seq(
    RowPartial(0, 0, 0, 0, 8, rowLast = true, contributorMask = 0x0001,
      segmentIds = 0 until 8)
  ))

  val all: Seq[Fixture] = Seq(
    full8, tail44, tail2222, pad3_1, empty_pc_row, empty_batch, last_short_batch,
    same_local_row_next_batch, multi_wave_same_y, explicit_zero, eight_x_segments
  )
}
