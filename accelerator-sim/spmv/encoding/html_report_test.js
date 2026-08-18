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
if (report.config.columnSliceCount !== undefined &&
    (report.physicalToOriginalRows.length !== report.shape.rows ||
     new Set(report.physicalToOriginalRows).size !== report.shape.rows ||
     report.physicalToOriginalRows.some((row) => !Number.isInteger(row) || row < 0 ||
       row >= report.shape.rows))) {
  throw new Error("physicalToOriginalRows 不是合法的原始行排列");
}
if (report.config.accumulationContexts !== 8 ||
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
      (slot[8] && (slot[11] >= report.config.accumulationContexts ||
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
