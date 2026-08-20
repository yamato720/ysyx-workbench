#include "fixtures.hpp"
#include "product_beat_golden.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace accelerator_sim::spmv::encoding::cuperflow {
namespace {

struct FixtureProductBeats {
  int selector = 0;
  std::vector<CuperflowProductBeatGolden> beats;
};

const std::vector<FixtureProductBeats>& fixtureProductBeats() {
  static const std::vector<FixtureProductBeats> payloads = [] {
    std::vector<FixtureProductBeats> result;
    for (const fixtures::V0Fixture& fixture : fixtures::v0()) {
      std::vector<double> x(fixture.matrix.columns);
      for (std::size_t column = 0; column < x.size(); ++column) {
        x[column] = 0.25 + static_cast<double>(column);
      }
      result.push_back({static_cast<int>(fixture.kind),
                        makeProductBeatGolden(encode(fixture.matrix, fixture.config), x)});
    }
    return result;
  }();
  return payloads;
}

const std::vector<CuperflowProductBeatGolden>& selectedProductBeats(int selector) {
  const auto& payloads = fixtureProductBeats();
  const auto selected = std::find_if(payloads.begin(), payloads.end(), [selector](const auto& payload) {
    return payload.selector == selector;
  });
  if (selected == payloads.end()) {
    throw std::invalid_argument("Cuperflow ProductBeat DPI fixture selector 非法");
  }
  return selected->beats;
}

std::size_t cursor = 0;
bool holding = false;
int fixtureSelector = 0;
CuperflowProductBeatGolden current{};

void copyBeat(const CuperflowProductBeatGolden& beat, int* pc, int* wave, int* batch,
              int* beatSeq, int* laneValid, int* chunkMode, int* localRow0, int* localRow1,
              int* localRow2, int* localRow3, int* localRow4, int* localRow5, int* localRow6,
              int* localRow7, int* rowLast0, int* rowLast1, int* rowLast2, int* rowLast3,
              int* rowLast4, int* rowLast5, int* rowLast6, int* rowLast7,
              unsigned long long* product0, unsigned long long* product1,
              unsigned long long* product2, unsigned long long* product3,
              unsigned long long* product4, unsigned long long* product5,
              unsigned long long* product6, unsigned long long* product7) {
  *pc = beat.pc;
  *wave = beat.wave;
  *batch = beat.batch;
  *beatSeq = static_cast<int>(beat.beatSeq);
  *laneValid = beat.laneValid;
  *chunkMode = beat.chunkMode;
  int* const localRows[] = {localRow0, localRow1, localRow2, localRow3,
                            localRow4, localRow5, localRow6, localRow7};
  int* const rowLast[] = {rowLast0, rowLast1, rowLast2, rowLast3,
                          rowLast4, rowLast5, rowLast6, rowLast7};
  unsigned long long* const products[] = {product0, product1, product2, product3,
                                            product4, product5, product6, product7};
  for (std::size_t lane = 0; lane < kLanesPerBeat; ++lane) {
    *localRows[lane] = beat.localRow[lane];
    *rowLast[lane] = beat.rowLast[lane] ? 1 : 0;
    *products[lane] = beat.product[lane];
  }
}

}  // namespace
}  // namespace accelerator_sim::spmv::encoding::cuperflow

/** Verilator-only ProductBeat source for future standalone L1/L2 contract tests.
 *
 * `ready` 是上一拍的 Decoupled 消费结果。未消费的 beat 保持不变；所有 product 均由
 * V0 fixture 的 package + X golden 计算，不走 input-mul RTL 或正式 FPGA 路径。
 */
extern "C" void spmv_cuperflow_product_beat_dpi_reset(int fixture) {
  using namespace accelerator_sim::spmv::encoding::cuperflow;
  (void)selectedProductBeats(fixture);
  cursor = 0;
  holding = false;
  fixtureSelector = fixture;
  current = {};
}

extern "C" void spmv_cuperflow_product_beat_dpi_step(
    int ready, int* valid, int* pc, int* wave, int* batch, int* beatSeq, int* laneValid,
    int* chunkMode, int* localRow0, int* localRow1, int* localRow2, int* localRow3,
    int* localRow4, int* localRow5, int* localRow6, int* localRow7, int* rowLast0,
    int* rowLast1, int* rowLast2, int* rowLast3, int* rowLast4, int* rowLast5,
    int* rowLast6, int* rowLast7, unsigned long long* product0,
    unsigned long long* product1, unsigned long long* product2, unsigned long long* product3,
    unsigned long long* product4, unsigned long long* product5, unsigned long long* product6,
    unsigned long long* product7) {
  using namespace accelerator_sim::spmv::encoding::cuperflow;
  const auto& beats = selectedProductBeats(fixtureSelector);
  if (!holding || ready != 0) {
    if (cursor < beats.size()) {
      current = beats[cursor++];
      holding = true;
    } else {
      current = {};
      holding = false;
    }
  }
  *valid = holding ? 1 : 0;
  copyBeat(current, pc, wave, batch, beatSeq, laneValid, chunkMode, localRow0, localRow1,
      localRow2, localRow3, localRow4, localRow5, localRow6, localRow7, rowLast0, rowLast1,
      rowLast2, rowLast3, rowLast4, rowLast5, rowLast6, rowLast7, product0, product1,
      product2, product3, product4, product5, product6, product7);
}
