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
if (report.config.accumulationContexts !== 8 ||
    report.batchStats.length !== report.stats.batchCount ||
    report.channelStats.length !== report.config.hbmChannels ||
    report.slots.some((slot) => slot.length !== 18 || !/^0x[0-9a-f]{16}$/.test(slot[7]) ||
      slot[12] < 0 || slot[12] > 0xffff ||
      (slot[8] && (slot[11] >= report.config.accumulationContexts ||
        !Number.isInteger(slot[13]) || typeof slot[17] !== "string")))) {
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
