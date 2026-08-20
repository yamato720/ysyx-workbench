"use strict";

const fs = require("fs");
const vm = require("vm");

if (process.argv.length !== 3) {
  throw new Error("用法: node encoding/html_report_test.js <report.html>");
}

const path = process.argv[2];
const html = fs.readFileSync(path, "utf8");
const scriptMatch = html.match(/<script>([\s\S]*)<\/script>/);
if (scriptMatch === null) {
  throw new Error("Cuper HTML 报告缺少 script");
}
new vm.Script(scriptMatch[1], {filename: path + ":script"});

const reportStart = scriptMatch[1].indexOf("const report=");
const reportEnd = scriptMatch[1].indexOf(";\nconst I=", reportStart);
if (reportStart < 0 || reportEnd < 0) {
  throw new Error("Cuper HTML 报告缺少内嵌 package 数据");
}
const report = JSON.parse(scriptMatch[1].slice(reportStart + "const report=".length, reportEnd));
if (report.vectorReport !== `${report.dataset}-x.html`) {
  throw new Error("Cuper A HTML 报告的 X 页面链接错误");
}
if (report.detailSample !== undefined) {
  if (report.slots !== undefined) {
    throw new Error("Cuperflow 摘要报告不应嵌入全量 slots");
  }
  if (!Array.isArray(report.batchStats) ||
      report.batchStats.length !== report.stats.batchCount ||
      !Array.isArray(report.batchChannelStats) ||
      report.batchChannelStats.length !== report.stats.batchCount ||
      !Array.isArray(report.channelStats) ||
      report.channelStats.length !== report.config.hbmChannels) {
    throw new Error("Cuperflow 摘要报告缺少 batch 或 HBM 统计");
  }
  let totalBeats = 0;
  let totalMatrix = 0;
  let totalZeroFill = 0;
  const channelMatrix = Array.from({length: report.config.hbmChannels}, () => 0);
  const channelZeroFill = Array.from({length: report.config.hbmChannels}, () => 0);
  for (let batch = 0; batch < report.stats.batchCount; ++batch) {
    const batchChannels = report.batchChannelStats[batch];
    if (!Array.isArray(batchChannels) || batchChannels.length !== report.config.hbmChannels) {
      throw new Error("Cuperflow batchChannelStats 的 HBM 数量错误");
    }
    let batchMatrix = 0;
    let batchZeroFill = 0;
    for (let channel = 0; channel < report.config.hbmChannels; ++channel) {
      const stats = batchChannels[channel];
      if (!Array.isArray(stats) || stats.length !== 4 || !Number.isInteger(stats[0]) ||
          stats[0] < 0 || !Number.isInteger(stats[1]) || stats[1] < 0 ||
          !Number.isInteger(stats[2]) || stats[2] < 0 || !Array.isArray(stats[3]) ||
          stats[3].length !== 8 || stats[0] * 8 !== stats[1] + stats[2]) {
        throw new Error("Cuperflow batchChannelStats 的 beat 或 slot 统计错误");
      }
      const laneMatrix = stats[3].reduce((sum, lane) =>
        sum + (Array.isArray(lane) && lane.length === 2 && Number.isInteger(lane[0]) ? lane[0] : NaN), 0);
      const laneZeroFill = stats[3].reduce((sum, lane) =>
        sum + (Array.isArray(lane) && lane.length === 2 && Number.isInteger(lane[1]) ? lane[1] : NaN), 0);
      if (!Number.isFinite(laneMatrix) || !Number.isFinite(laneZeroFill) ||
          laneMatrix !== stats[1] || laneZeroFill !== stats[2]) {
        throw new Error("Cuperflow lane 统计与 HBM 汇总不一致");
      }
      totalBeats += stats[0];
      totalMatrix += stats[1];
      totalZeroFill += stats[2];
      batchMatrix += stats[1];
      batchZeroFill += stats[2];
      channelMatrix[channel] += stats[1];
      channelZeroFill[channel] += stats[2];
    }
    if (report.batchStats[batch][0] !== batchMatrix ||
        report.batchStats[batch][1] !== batchZeroFill) {
      throw new Error("Cuperflow batchStats 与 HBM 汇总不一致");
    }
  }
  if (totalBeats !== report.stats.totalBeats || totalMatrix !== report.stats.matrixSlots ||
      totalZeroFill !== report.stats.zeroFillSlots ||
      report.channelStats.some((stats, channel) => !Array.isArray(stats) || stats.length !== 2 ||
        stats[0] !== channelMatrix[channel] || stats[1] !== channelZeroFill[channel])) {
    throw new Error("Cuperflow 摘要统计与 package 总计不一致");
  }
  const l1 = report.stats;
  if (!Array.isArray(l1.rowPartialBeatCounts) || l1.rowPartialBeatCounts.length !== 3 ||
      l1.rowPartialBeatCounts.some(value => !Number.isInteger(value) || value < 0) ||
      l1.rowPartialBeatCounts.reduce((sum, value) => sum + value, 0) !== l1.totalBeats ||
      !l1.chunkInterBeatDistance || !Number.isInteger(l1.chunkInterBeatDistance.count) ||
      !Number.isInteger(l1.chunkInterBeatDistance.total) ||
      !Number.isInteger(l1.chunkInterBeatDistance.minimum) ||
      !Number.isInteger(l1.chunkInterBeatDistance.maximum) ||
      !Number.isInteger(l1.chunkInterBeatDistance.belowCandidateFaddLatency) ||
      l1.chunkInterBeatDistance.candidateFaddLatency !== 4 ||
      l1.chunkInterBeatDistance.belowCandidateFaddLatency > l1.chunkInterBeatDistance.count ||
      (l1.chunkInterBeatDistance.count === 0 &&
       (l1.chunkInterBeatDistance.total !== 0 || l1.chunkInterBeatDistance.minimum !== 0 ||
        l1.chunkInterBeatDistance.maximum !== 0)) ||
      (l1.chunkInterBeatDistance.count > 0 &&
       (l1.chunkInterBeatDistance.minimum > l1.chunkInterBeatDistance.maximum ||
        l1.chunkInterBeatDistance.total < l1.chunkInterBeatDistance.count)) ||
      !Array.isArray(l1.contributorPopcountHistogram) ||
      l1.contributorPopcountHistogram.length !== 17 ||
      l1.contributorPopcountHistogram.some(value => !Number.isInteger(value) || value < 0) ||
      !Number.isInteger(l1.completionRobPeak) || l1.completionRobPeak < 0 ||
      !Number.isInteger(report.config.activeXPayloadGroupCount) ||
      report.config.activeXPayloadGroupCount < 0 ||
      report.config.activeXPayloadGroupCount > report.config.sliceGroupCount ||
      l1.xPayloadLoadCount !== report.config.activeXPayloadGroupCount ||
      l1.expectedXPayloadLoadCount !== report.config.activeXPayloadGroupCount ||
      !Array.isArray(report.pcL1Stats) || report.pcL1Stats.length !== report.config.hbmChannels ||
      report.pcL1Stats.some(stats => !Array.isArray(stats) || stats.length !== 4 ||
        stats.some(value => !Number.isInteger(value) || value < 0)) ||
      !Array.isArray(report.waveBatchL1Stats) ||
      report.waveBatchL1Stats.length !== report.stats.batchCount * report.config.contributorWaveCount ||
      report.waveBatchL1Stats.some(stats => !Array.isArray(stats) || stats.length !== 2 ||
        stats.some(value => !Number.isInteger(value) || value < 0))) {
    throw new Error("Cuperflow V0 L1 分析统计结构不完整");
  }
  const totalPcABeats = report.pcL1Stats.reduce((sum, stats) => sum + stats[0], 0);
  const totalPcSlots = report.pcL1Stats.reduce((sum, stats) => sum + stats[1], 0);
  const totalPcRows = report.pcL1Stats.reduce((sum, stats) => sum + stats[2], 0);
  const totalPcEmpty = report.pcL1Stats.reduce((sum, stats) => sum + stats[3], 0);
  const histogramRows = l1.contributorPopcountHistogram.reduce((sum, value) => sum + value, 0);
  const histogramContributors = l1.contributorPopcountHistogram.reduce(
    (sum, value, popcount) => sum + value * popcount, 0);
  const expectedHistogramRows = Array.from({length: report.stats.batchCount}, (_, batch) =>
    Math.min(report.config.rowBatchSize, report.shape.rows - batch * report.config.rowBatchSize)
  ).reduce((sum, rows) => sum + rows, 0) * report.config.contributorWaveCount;
  if (totalPcABeats !== l1.totalBeats || totalPcSlots !== l1.matrixSlots ||
      totalPcEmpty !== l1.emptyBatchCount || totalPcRows !== histogramContributors ||
      histogramRows !== expectedHistogramRows ||
      report.waveBatchL1Stats.reduce((sum, stats) => sum + stats[0], 0) !==
        l1.contributorPopcountHistogram.slice(1).reduce((sum, value) => sum + value, 0)) {
    throw new Error("Cuperflow V0 L1 分析汇总不一致");
  }
  const sample = report.detailSample;
  if (sample.batch !== 0 || sample.channel !== 0 || sample.slot !== 0 ||
      (sample.data !== null &&
       (!Array.isArray(sample.data) || sample.data.length !== 22 || sample.data[1] !== 0 ||
        sample.data[2] !== 0 || sample.data[5] !== 0 ||
        !/^0x[0-9a-f]{16}$/.test(sample.data[7]) ||
        typeof sample.data[12] !== "boolean" || !Number.isInteger(sample.data[13]) ||
        sample.data[13] < 0 || sample.data[13] > 2 ||
        !Number.isInteger(sample.data[14]) || sample.data[14] < 0 || sample.data[14] >= 8192))) {
    throw new Error("Cuperflow Slot 0 示例数据结构不完整");
  }
  for (const id of [
    "vectorReport", "packageView", "batchGrid", "batchView", "channelGrid", "channelView",
    "laneGrid", "sampleAction", "detailButton", "slotView", "bitfield", "rawHex",
  ]) {
    if (!html.includes(`id="${id}"`)) {
      throw new Error(`Cuperflow 摘要 HTML 报告缺少 ${id}`);
    }
  }
  console.log(`[spmv-encoding-html-test] ${path} summary PASS`);
  process.exit(0);
}
const expectedSlots = report.stats.matrixSlots + report.stats.zeroFillSlots;
if (report.slots.length !== expectedSlots) {
  throw new Error(`slot 数量错误: ${report.slots.length} != ${expectedSlots}`);
}
if (report.channelBatchPointers.length !== report.config.hbmChannels) {
  throw new Error("channelBatchPointers 的 HBM channel 数量错误");
}
const channelLengths = [];
for (const pointers of report.channelBatchPointers) {
  if (pointers.length !== report.stats.batchCount + 1 || pointers[0] !== 0 ||
      pointers.some((pointer, index) => index > 0 && pointer < pointers[index - 1])) {
    throw new Error("channelBatchPointers 不满足 per-HBM 累计边界语义");
  }
  channelLengths.push(pointers.at(-1));
}
const totalBeats = channelLengths.reduce((sum, length) => sum + length, 0);
if (totalBeats !== report.stats.totalBeats ||
    Math.min(...channelLengths) !== report.stats.minBeatsPerChannel ||
    Math.max(...channelLengths) !== report.stats.maxBeatsPerChannel) {
  throw new Error("channelBatchPointers 与动态 HBM beat 统计不一致");
}
if (report.config.columnSliceCount !== undefined) {
  const groupSegmentCount = report.stats.batchCount * report.config.sliceGroupCount;
  const invalidGroupRanges = report.channelLaneSliceGroupRanges.some((ranges, channel) =>
    ranges.length !== groupSegmentCount ||
    ranges.some((rangeSet) => rangeSet.some((range) =>
      range.length !== 2 || range[0] > range[1] || range[1] > channelLengths[channel])));
  if (report.config.sliceGroupSize === 0 ||
      report.channelLaneSliceGroupRanges.length !== report.config.hbmChannels ||
      invalidGroupRanges) {
    throw new Error("channelLaneSliceGroupRanges 不满足 slice group 边界语义");
  }
  if (report.sliceGroupChannels.length !== report.config.sliceGroupCount ||
      report.channelSliceGroups.length !== report.config.hbmChannels ||
      report.sliceGroupChannels.some((channel, group) =>
        channel !== group % report.config.hbmChannels) ||
      report.channelSliceGroups.some((groups, channel) =>
        groups.some((group) => report.sliceGroupChannels[group] !== channel))) {
    throw new Error("slice group 没有映射到互不重叠的 HBM X range");
  }
}
if (report.config.xSegmentLimit !== undefined) {
  if (report.config.xSegmentLimit !== 8 ||
      !Array.isArray(report.xSegmentsByGroup) ||
      report.xSegmentsByGroup.length !== report.config.sliceGroupCount ||
      report.xSegmentsByGroup.some((segments) => segments.length > report.config.xSegmentLimit ||
        segments.some((segment) => segment.length !== 2 || segment[1] <= 0 ||
          segment[0] + segment[1] > report.config.xRangeMaxElements)) ||
      report.slots.some((slot) => {
        if (!slot[8]) return false;
        const group = Math.floor(slot[18] / report.config.sliceGroupSize);
        const segment = report.xSegmentsByGroup[group]?.[slot[11]];
        return segment === undefined || slot[9] < segment[0] ||
          slot[9] >= segment[0] + segment[1];
      })) {
    throw new Error("Cuperflow slot segmentId 或 X 段 descriptor 不一致");
  }
}
if (report.config.columnSliceCount !== undefined &&
    (report.physicalToOriginalRows.length !== report.shape.rows ||
     new Set(report.physicalToOriginalRows).size !== report.shape.rows ||
     report.physicalToOriginalRows.some((row) => !Number.isInteger(row) || row < 0 ||
       row >= report.shape.rows))) {
  throw new Error("physicalToOriginalRows 不是合法的原始行排列");
}
const tagLimit = report.config.xSegmentLimit ?? report.config.accumulationContexts;
if (tagLimit !== 8 ||
    report.batchStats.length !== report.stats.batchCount ||
    report.channelStats.length !== report.config.hbmChannels ||
    report.slots.some((slot) =>
      (report.config.columnSliceCount !== undefined ? slot.length !== 20 : slot.length !== 18) ||
      !/^0x[0-9a-f]{16}$/.test(slot[7]) ||
      slot[12] < 0 || slot[12] > 0xffff ||
      (report.config.columnSliceCount !== undefined &&
        ((slot[8] ? (!Number.isInteger(slot[18]) || slot[18] < 0 ||
                     slot[18] >= report.config.columnSliceCount) : slot[18] !== null) ||
         (slot[8] ? (!Number.isInteger(slot[19]) || slot[19] < 0 ||
                     slot[19] >= report.shape.rows) : slot[19] !== null))) ||
      (slot[8] && (slot[11] >= tagLimit ||
        !Number.isInteger(slot[13]) || !Number.isInteger(slot[10]) ||
        slot[10] < 0 || slot[10] >= report.shape.columns || typeof slot[17] !== "string")))) {
  throw new Error("batch、channel 或 slot 详细数据结构不完整");
}
for (const id of [
  "vectorReport", "packageView", "batchGrid", "batchView", "channelGrid", "channelView",
  "slotMatrix", "matrixMode", "beatPageSize", "zeroFillPrev", "zeroFillNext",
  "slotView", "bitfield", "peerLanes",
]) {
  if (!html.includes(`id="${id}"`)) {
    throw new Error(`Cuper HTML 报告缺少 ${id}`);
  }
}

console.log(`[spmv-encoding-html-test] ${path} slots=${report.slots.length} PASS`);
