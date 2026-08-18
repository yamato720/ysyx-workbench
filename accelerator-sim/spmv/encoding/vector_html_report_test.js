"use strict";

const fs = require("fs");
const vm = require("vm");

if (process.argv.length !== 3) {
  throw new Error("用法: node encoding/vector_html_report_test.js <x-report.html>");
}

const path = process.argv[2];
const html = fs.readFileSync(path, "utf8");
const scriptMatch = html.match(/<script>([\s\S]*)<\/script>/);
if (scriptMatch === null) {
  throw new Error("Cuper X HTML 报告缺少 script");
}
new vm.Script(scriptMatch[1], {filename: path + ":script"});

const reportStart = scriptMatch[1].indexOf("const report=");
const reportEnd = scriptMatch[1].indexOf(";\nconst I=", reportStart);
if (reportStart < 0 || reportEnd < 0) {
  throw new Error("Cuper X HTML 报告缺少内嵌 vector package 数据");
}
const report = JSON.parse(scriptMatch[1].slice(reportStart + "const report=".length, reportEnd));
if (report.matrixReport !== `${report.dataset}.html`) {
  throw new Error("Cuper X HTML 报告的 A 页面链接错误");
}
const expectedElements = report.stats.payloadBeats * report.config.lanesPerBeat;
if (report.elements.length !== expectedElements) {
  throw new Error(`X element 数量错误: ${report.elements.length} != ${expectedElements}`);
}
if (report.batchPointers.length !== report.stats.batchCount + 1 ||
    report.batchPointers[0] !== 0 ||
    report.batchPointers.at(-1) !== report.stats.payloadBeats ||
    report.batchPointers.some((pointer, index) =>
      index > 0 && pointer < report.batchPointers[index - 1])) {
  throw new Error("Cuper X batchPointers 不满足累计边界语义");
}
const encodedHexDigits = report.config.encodedBits === 64 ? 16 : 8;
const encodedBitsPattern = new RegExp(`^0x[0-9a-f]{${encodedHexDigits}}$`);
if (report.elements.some((element) => element.length !== 12 ||
    !encodedBitsPattern.test(element[10]))) {
  throw new Error("Cuper X element 数据结构不完整");
}
if (report.channelXRanges !== undefined) {
  const expectedRangeCount = report.config.sliceGroupCount;
  if (report.channelXRanges.length !== report.config.coreCount ||
      report.channelXRanges.flat().length !== expectedRangeCount ||
      report.channelXRanges.some((ranges, channel) => ranges.some((range) =>
        range.length !== 5 || range[0] % report.config.coreCount !== channel ||
        range[2] > report.config.xRangeMaxElements || range[3] > range[4]))) {
    throw new Error("Cuper X 没有形成互不重叠且不超过 8192 元素的 per-HBM range");
  }
}
if (report.encodedXRanges !== undefined) {
  if (report.encodedXRanges.length !== report.config.coreCount ||
      report.encodedXRanges.flat().length !== report.config.sliceGroupCount ||
      report.encodedXRanges.some((ranges) => ranges.some((range) =>
        range.length !== 8 || range[3] !== range[4] + range[5] || range[6] > range[7]))) {
    throw new Error("Cuperflow encoded X range 的 token 统计或边界错误");
  }
  const ranges = report.encodedXRanges.flat();
  if (ranges.reduce((total, range) => total + range[3], 0) !== report.stats.encodedWordCount ||
      ranges.reduce((total, range) => total + range[5], 0) !== report.stats.markerCount) {
    throw new Error("Cuperflow encoded X range 与 marker 总数不一致");
  }
}
const physicalSlots = report.stats.encodedPayloadBeats * report.config.lanesPerBeat;
if (report.stats.encodedValueCount > report.stats.validElements ||
    report.stats.encodedWordCount !== report.stats.encodedValueCount + report.stats.markerCount ||
    physicalSlots < report.stats.encodedWordCount) {
  throw new Error("Cuper X 利用率统计的 value、marker 或物理 slot 数量错误");
}
const valid = report.elements.filter((element) => !element[7]);
if (valid.length !== report.stats.validElements ||
    valid.some((element) => element[5] !== element[0] ||
      element[11] !== element[6] % report.config.partitionFactor)) {
  throw new Error("Cuper X 原列顺序或 cyclic bank 映射错误");
}
for (const id of [
  "matrixReport", "mapFlow", "coreFlow", "bankFlow", "computeFlow",
  "packageView", "batchGrid", "batchView", "cellMode",
  "packetRows", "elementView", "conversion", "replicas", "elementDetails",
]) {
  if (!html.includes(`id="${id}"`)) {
    throw new Error(`Cuper X HTML 报告缺少 ${id}`);
  }
}
const xStage = html.includes("HBM X range → local_X") ?
  "HBM X range → local_X" : "X broadcast → local_X";
const coreOrder = ["PE_Param header", "Batch start", xStage, "Batch end", "A × X"];
let previous = -1;
for (const stage of coreOrder) {
  const current = html.indexOf(stage);
  if (current <= previous) {
    throw new Error(`Cuper Core 数据流顺序缺失或错误: ${stage}`);
  }
  previous = current;
}

console.log(`[spmv-encoding-x-html-test] ${path} elements=${report.elements.length} PASS`);
