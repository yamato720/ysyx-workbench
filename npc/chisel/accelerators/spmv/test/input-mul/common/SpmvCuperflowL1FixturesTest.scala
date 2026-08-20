package accelerators.spmv.inputmul.common

import org.scalatest.flatspec.AnyFlatSpec

class SpmvCuperflowL1FixturesTest extends AnyFlatSpec {
  "SpmvCuperflowL1Fixtures" should "冻结 V1/V2 可直接引用的具名 RowPartial 输入" in {
    import SpmvCuperflowL1Fixtures._

    assert(all.map(_.name) == Seq(
      "full8", "tail44", "tail2222", "pad3_1", "empty_pc_row", "empty_batch",
      "last_short_batch", "same_local_row_next_batch", "multi_wave_same_y", "explicit_zero",
      "eight_x_segments"
    ))
    assert(empty_batch.emptyBatch && empty_batch.partials.isEmpty)
    assert(last_short_batch.shortFinalBatch && last_short_batch.partials.head.batch == 1)
    assert(same_local_row_next_batch.partials.map(partial => (partial.batch, partial.localRow)) ==
      Seq((0, 0), (1, 0)))
    assert(multi_wave_same_y.partials.map(partial => (partial.wave, partial.localRow)) ==
      Seq((0, 0), (1, 0)))
    assert(eight_x_segments.partials.head.segmentIds == (0 until 8))
  }
}
