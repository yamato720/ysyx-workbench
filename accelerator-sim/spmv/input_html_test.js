"use strict";

const fs = require("fs");
const vm = require("vm");

if (process.argv.length !== 4) {
  throw new Error("用法: node input_html_test.js <performance.html> <pipeline.html>");
}

const performancePath = process.argv[2];
const pipelinePath = process.argv[3];
const performanceHtml = fs.readFileSync(performancePath, "utf8");
const pipelineHtml = fs.readFileSync(pipelinePath, "utf8");

if (!performanceHtml.includes("href=\"pipeline.html\" target=\"_blank\" rel=\"noopener\"")) {
  throw new Error("性能主页缺少到流水页的安全新窗口链接");
}
if (!pipelineHtml.includes("href=\"performance.html\"")) {
  throw new Error("流水页缺少返回性能主页的链接");
}

for (const marker of [
  "SPMV 输入性能报告", "执行总览", "输入配置", "A 通道负载分布", "消费端校验",
  "16 路 A reader", "1 路 X reader", "16 个消费端", "X 原子广播",
]) {
  if (!performanceHtml.includes(marker)) {
    throw new Error(`性能主页缺少 ${marker}`);
  }
}

const consumerMatches = [...performanceHtml.matchAll(/<tr><td>C(\d+) \/ A(\d+)<\/td>/g)];
if (consumerMatches.length !== 16 ||
    consumerMatches.some((match, index) => Number(match[1]) !== index || Number(match[2]) !== index)) {
  throw new Error("性能主页没有按 C0/A0 到 C15/A15 展示 16 个消费端");
}

const scriptMatch = pipelineHtml.match(/<script>([\s\S]*)<\/script>/);
if (scriptMatch === null) {
  throw new Error("流水页缺少 script");
}
new vm.Script(scriptMatch[1], {filename: pipelinePath + ":script"});

const traceStart = scriptMatch[1].indexOf("const trace=");
const traceEnd = scriptMatch[1].indexOf(";const colors=", traceStart);
if (traceStart < 0 || traceEnd < 0) {
  throw new Error("流水页缺少内嵌 trace 数据");
}
const trace = JSON.parse(scriptMatch[1].slice(traceStart + "const trace=".length, traceEnd));

if (trace.aExpected.length !== 16 || trace.xExpected <= 0) {
  throw new Error("流水页没有表示 16 路 A 和 1 路 X 输入");
}
if (trace.cycles <= 0 || trace.records.length !== trace.cycles) {
  throw new Error("流水记录数量与周期数不一致");
}

const allAMask = 0xffff;
const aRequestMask = trace.records.reduce((mask, record) => mask | record.q, 0);
const aAddressMask = trace.records.reduce((mask, record) => mask | record.a, 0);
const aDataMask = trace.records.reduce((mask, record) => mask | record.r, 0);
const aDoneMask = trace.records.reduce((mask, record) => mask | record.d, 0);
if (aRequestMask !== allAMask || aAddressMask !== allAMask ||
    aDataMask !== allAMask || aDoneMask !== allAMask) {
  throw new Error("流水记录没有覆盖全部 16 路 A 的请求、AR、R 和完成事件");
}
if (!trace.records.some((record) => record.xq) ||
    !trace.records.some((record) => record.xa) ||
    !trace.records.some((record) => record.xr) ||
    !trace.records.some((record) => record.xd)) {
  throw new Error("流水记录没有覆盖 X 的请求、AR、R 和完成事件");
}

if (trace.records.length < 3 || trace.records[0].q !== allAMask || !trace.records[0].xq ||
    trace.records[1].a !== allAMask || !trace.records[1].xa ||
    trace.records[2].r !== allAMask || !trace.records[2].xr) {
  throw new Error("满带宽流水必须在 cycle 0/1/2 连续完成全部 Q、AR 和首个 R");
}

function assertContinuousData(name, expectedBeats, predicate) {
  const dataCycles = trace.records.filter(predicate).map((record) => record.c);
  if (dataCycles.length !== expectedBeats || dataCycles[0] !== 2 ||
      dataCycles.some((cycle, index) => index > 0 && cycle !== dataCycles[index - 1] + 1)) {
    throw new Error(`${name} 不是从 cycle 2 开始逐拍连续的满带宽 R 输入`);
  }
}

trace.aExpected.forEach((beats, lane) => {
  const bit = 1 << lane;
  assertContinuousData(`A${lane}`, beats, (record) => (record.r & bit) !== 0);
});
assertContinuousData("X", trace.xExpected, (record) => Boolean(record.xr));

for (const id of ["search", "zoom", "rows", "hscroll", "hscrollThumb"]) {
  if (!pipelineHtml.includes(`id=\"${id}\"`)) {
    throw new Error(`流水页缺少 ${id} 交互控件`);
  }
}
for (const marker of ["height:100vh", "overflow:auto", "@media(max-width:700px)"]) {
  if (!pipelineHtml.includes(marker)) {
    throw new Error(`流水页缺少布局约束 ${marker}`);
  }
}

console.log(
    `[spmv-input-html-test] ${performancePath} consumers=16 lanes=17 cycles=${trace.cycles} PASS`);
