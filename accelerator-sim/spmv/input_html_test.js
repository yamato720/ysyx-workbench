"use strict";

const fs = require("fs");
const vm = require("vm");

if (process.argv.length !== 5) {
  throw new Error(
      "用法: node input_html_test.js <performance.html> <input-pipeline.html> <timing-pipeline.html>");
}

const performancePath = process.argv[2];
const inputPipelinePath = process.argv[3];
const timingPipelinePath = process.argv[4];
const performanceHtml = fs.readFileSync(performancePath, "utf8");
const inputPipelineHtml = fs.readFileSync(inputPipelinePath, "utf8");
const timingPipelineHtml = fs.readFileSync(timingPipelinePath, "utf8");

for (const child of ["input-pipeline.html", "timing-pipeline.html"]) {
  if (!performanceHtml.includes(`href=\"${child}\" target=\"_blank\" rel=\"noopener\"`)) {
    throw new Error(`性能主页缺少到 ${child} 的安全新窗口链接`);
  }
}
if (!inputPipelineHtml.includes("href=\"performance.html\"")) {
  throw new Error("输入流水页缺少返回性能主页的链接");
}
for (const sibling of ["performance.html", "input-pipeline.html"]) {
  if (!timingPipelineHtml.includes(`href=\"${sibling}\"`)) {
    throw new Error(`计算时序页缺少到 ${sibling} 的链接`);
  }
}

for (const marker of [
  "SPMV 输入性能报告", "执行总览", "输入配置", "A 通道负载分布", "消费端校验",
  "16 路 A reader", "2 路 X reader", "1 路 Ctrl reader", "16 个消费端",
  "X 双 beat 原子广播", "Ctrl 广播",
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

function parseInlineJson(html, name, suffix, sourceName) {
  const scriptMatch = html.match(/<script>([\s\S]*)<\/script>/);
  if (scriptMatch === null) throw new Error(`${sourceName} 缺少 script`);
  new vm.Script(scriptMatch[1], {filename: sourceName + ":script"});
  const start = scriptMatch[1].indexOf(`const ${name}=`);
  const end = scriptMatch[1].indexOf(suffix, start);
  if (start < 0 || end < 0) throw new Error(`${sourceName} 缺少内嵌 ${name} 数据`);
  return JSON.parse(scriptMatch[1].slice(start + `const ${name}=`.length, end));
}

const trace = parseInlineJson(inputPipelineHtml, "trace", ";const colors=", inputPipelinePath);
if (trace.aExpected.length !== 16 || trace.xExpected.length !== 2 ||
    trace.xExpected.some((beats) => beats <= 0) ||
    !Number.isInteger(trace.ctrlExpected) || trace.ctrlExpected <= 0 ||
    !Number.isInteger(trace.batchCount) || trace.batchCount <= 0 ||
    trace.cycles <= 0 || trace.records.length !== trace.cycles) {
  throw new Error("输入流水 trace 没有完整表示 16 路 A、双路 X、Ctrl 与 Cuper 窗口");
}
if (!inputPipelineHtml.includes("const streams=[{name:'Ctrl'")) {
  throw new Error("输入流水泳道没有按 Ctrl、X0/X1、A0..A15 排列");
}

const allAMask = 0xffff;
for (const field of ["q", "a", "r", "d"]) {
  const mask = trace.records.reduce((value, record) => value | record[field], 0);
  if (mask !== allAMask) throw new Error(`输入流水的 ${field} 没有覆盖全部 A reader`);
}
for (const field of ["xq", "xa", "xr", "xd"]) {
  const mask = trace.records.reduce((value, record) => value | record[field], 0);
  if (mask !== 0x3) throw new Error(`输入流水的 ${field} 没有覆盖 X0/X1`);
}
for (const field of ["cq", "ca", "cr", "cd"]) {
  const mask = trace.records.reduce((value, record) => value | record[field], 0);
  if (mask !== 0x1) throw new Error(`输入流水的 ${field} 没有覆盖 Ctrl`);
}

function firstCycle(predicate) {
  const record = trace.records.find(predicate);
  if (record === undefined) throw new Error("输入流水缺少预期阶段事件");
  return record.c;
}

function continuousRuns(name, expectedBeats, expectedRunCount, predicate) {
  const cycles = trace.records.filter(predicate).map((record) => record.c);
  const runs = [];
  for (const cycle of cycles) {
    const current = runs.at(-1);
    if (current !== undefined && cycle === current.at(-1) + 1) current.push(cycle);
    else runs.push([cycle]);
  }
  if (cycles.length !== expectedBeats || runs.length !== expectedRunCount ||
      runs.some((run) => run.length === 0 || run.some((cycle, index) =>
        index > 0 && cycle !== run[index - 1] + 1))) {
    throw new Error(`${name} 没有按 Cuper 窗口逐拍连续返回 R`);
  }
  return runs;
}

const ctrlQCycle = firstCycle((record) => record.cq !== 0);
const ctrlArCycle = firstCycle((record) => record.ca !== 0);
const ctrlRCycle = firstCycle((record) => record.cr !== 0);
if (ctrlQCycle !== 0 || ctrlArCycle !== 1 || ctrlRCycle !== 2) {
  throw new Error("Ctrl map 没有按 cycle 0/1/2 连续启动和返回");
}
continuousRuns("Ctrl", trace.ctrlExpected, 1, (record) => (record.cr & 1) !== 0);

trace.aExpected.forEach((beats, lane) => {
  const observed = trace.records.filter((record) => (record.r & (1 << lane)) !== 0).length;
  if (observed !== beats) throw new Error(`A${lane} 的 Cuper 多窗口 R beat 数不完整`);
});
const xRuns = trace.xExpected.map((beats, lane) => continuousRuns(
    `X${lane}`, beats, trace.batchCount, (record) => (record.xr & (1 << lane)) !== 0));
if (xRuns[0].some((run, index) => run[0] !== xRuns[1][index][0] ||
    run.at(-1) !== xRuns[1][index].at(-1))) {
  throw new Error("X0/X1 没有按每个 Cuper 窗口原子广播");
}
const xRequestCycles = trace.records.filter((record) => record.xq === 0x3).map((record) => record.c);
const aRequestCycles = trace.records.filter((record) => record.q !== 0).map((record) => record.c);
if (xRequestCycles.length !== trace.batchCount || aRequestCycles.length !== trace.batchCount ||
    xRequestCycles.some((cycle, index) => cycle !== xRuns[0][index][0] - 2) ||
    aRequestCycles.some((cycle, index) => cycle <= xRuns[0][index].at(-1))) {
  throw new Error("输入流水没有按 Ctrl -> (X0/X1 -> A0..A15)* 的 Cuper 窗口顺序执行");
}

for (const id of ["search", "zoom", "rows", "hscroll", "hscrollThumb"]) {
  if (!inputPipelineHtml.includes(`id=\"${id}\"`)) {
    throw new Error(`输入流水页缺少 ${id} 交互控件`);
  }
}
for (const id of ["overview", "windowSize", "windowStart", "rows", "hscroll", "hscrollThumb"]) {
  if (!timingPipelineHtml.includes(`id=\"${id}\"`)) {
    throw new Error(`乘法流水页缺少 ${id} 交互控件`);
  }
}
for (const marker of ["height:100vh", "overflow:auto", "@media(max-width:700px)"]) {
  if (!inputPipelineHtml.includes(marker) || !timingPipelineHtml.includes(marker)) {
    throw new Error(`流水页缺少共同布局约束 ${marker}`);
  }
}

const timingTrace = parseInlineJson(timingPipelineHtml, "timingTrace", ";\nconst colors=", timingPipelinePath);
for (const marker of [
  "SPMV FP64 乘法计算流水", "FMUL 流水判定", "PE 前端 II=1", "A 平均 II",
  "FMUL 请求", "最大在飞", "掩码对齐", "全 padding A beat", "IP 在飞",
  "const popcount=", "function coreStats", "stageMismatches=0", "batchSelect",
  "Cuper 窗口", "窗口间 X 装载不计为计算气泡",
]) {
  if (!timingPipelineHtml.includes(marker)) {
    throw new Error(`乘法流水页缺少 ${marker} 统计或可视化`);
  }
}
const jobCyclesMatch = performanceHtml.match(
    /<span>硬件周期<\/span><strong>(\d+)<\/strong><small>cycles<\/small>/);
const mulCyclesMatch = performanceHtml.match(
    /<span>FP64 乘法<\/span><strong>(\d+)<\/strong><small>(?:A 单遍乘法 IP 验证周期|Cuper 分窗口乘法 IP 验证周期)<\/small>/);
if (jobCyclesMatch === null || mulCyclesMatch === null || timingTrace.records.length === 0 ||
    timingTrace.records.length !== Number(mulCyclesMatch[1]) ||
    timingTrace.records.length > Number(jobCyclesMatch[1]) ||
    timingTrace.records.some((record, index) => record.c !== index)) {
  throw new Error("计算时序 trace 没有精确覆盖全部 Cuper 计算窗口");
}
if (timingTrace.coreCount !== 16 || timingTrace.lanesPerCore !== 8 ||
    !Array.isArray(timingTrace.batches) || timingTrace.batches.length !== trace.batchCount ||
    !Number.isInteger(timingTrace.expectedMultiply) || timingTrace.expectedMultiply <= 0) {
  throw new Error("计算时序没有冻结 16 PE、8 lane 和逐窗口 Cuper 合同");
}
const popcount = (mask) => {
  let value = mask >>> 0;
  let count = 0;
  while (value !== 0) {
    count += value & 1;
    value >>>= 1;
  }
  return count;
};
const slotMask = (1 << timingTrace.lanesPerCore) - 1;
const timingFields = ["v", "p", "x", "q", "r"];
for (const record of timingTrace.records) {
  if (!Number.isInteger(record.c) || !Number.isInteger(record.w) ||
      record.w < 0 || record.w >= timingTrace.batches.length ||
      !Number.isInteger(record.b) || !Number.isInteger(record.d) ||
      !Number.isInteger(record.ready) || !Number.isInteger(record.streams) ||
      !Number.isInteger(record.done) || (record.b & ~allAMask) !== 0 ||
      (record.d & ~allAMask) !== 0) {
    throw new Error("计算时序记录的 batch、PE 掩码或控制事件非法");
  }
  for (const field of timingFields) {
    if (!Array.isArray(record[field]) || record[field].length !== timingTrace.coreCount ||
        record[field].some((mask) => !Number.isInteger(mask) || mask < 0 || mask > slotMask)) {
      throw new Error(`计算时序记录的 ${field} 没有按 16 个 PE 导出 8-lane 掩码`);
    }
  }
}

let totalExpectedMultiply = 0;
for (const batch of timingTrace.batches) {
  if (!Number.isInteger(batch.index) || !Array.isArray(batch.expectedBeats) ||
      !Array.isArray(batch.expectedValid) || batch.expectedBeats.length !== timingTrace.coreCount ||
      batch.expectedValid.length !== timingTrace.coreCount ||
      !Number.isInteger(batch.expectedMultiply) || batch.expectedMultiply < 0) {
    throw new Error("计算时序的 batch 元数据不完整");
  }
  const records = timingTrace.records.filter((record) => record.w === batch.index);
  if (records.length === 0 || records.some((record, index) => index > 0 &&
      record.c !== records[index - 1].c + 1) || records.filter((record) => record.done !== 0).length !== 1 ||
      records.at(-1).done !== 1 || records[0].ready !== 1) {
    throw new Error(`Cuper batch ${batch.index} 的计算窗口没有完整排空`);
  }
  const countMasks = (field) => records.reduce((total, record) => total +
      record[field].reduce((sum, mask) => sum + popcount(mask), 0), 0);
  const expectedBeats = batch.expectedBeats.reduce((sum, count) => sum + count, 0);
  const expectedValid = batch.expectedValid.reduce((sum, count) => sum + count, 0);
  const expectedPadding = expectedBeats * timingTrace.lanesPerCore - expectedValid;
  const acceptedBeats = records.reduce((sum, record) => sum + popcount(record.b), 0);
  if (expectedValid !== batch.expectedMultiply || acceptedBeats !== expectedBeats ||
      countMasks("v") !== expectedValid || countMasks("p") !== expectedPadding ||
      countMasks("x") !== expectedValid || countMasks("q") !== expectedValid ||
      countMasks("r") !== expectedValid) {
    throw new Error(`Cuper batch ${batch.index} 的 A beat 或 FMUL lane 计数不一致`);
  }
  for (let core = 0; core < timingTrace.coreCount; ++core) {
    const bit = 1 << core;
    const pendingByLane = Array.from({length: timingTrace.lanesPerCore}, () => []);
    const acceptedCycles = [];
    let stagedMask = 0;
    let accepted = 0;
    let valid = 0;
    let padding = 0;
    let xReads = 0;
    let requests = 0;
    let responses = 0;
    let done = 0;
    let inFlight = 0;
    let peakInFlight = 0;
    for (const record of records) {
      const acceptedNow = (record.b & bit) !== 0;
      const validMask = record.v[core];
      const paddingMask = record.p[core];
      const xReadMask = record.x[core];
      const requestMask = record.q[core];
      const responseMask = record.r[core];
      if (requestMask !== stagedMask || xReadMask !== (acceptedNow ? validMask : 0) ||
          (acceptedNow && (validMask | paddingMask) !== slotMask) ||
          (!acceptedNow && (validMask !== 0 || paddingMask !== 0))) {
        throw new Error(`Cuper batch ${batch.index} PE${core} 的 A -> local_X -> FMUL 掩码流水错位`);
      }
      if (acceptedNow) {
        ++accepted;
        acceptedCycles.push(record.c);
        valid += popcount(validMask);
        padding += popcount(paddingMask);
      }
      xReads += popcount(xReadMask);
      requests += popcount(requestMask);
      responses += popcount(responseMask);
      inFlight += popcount(requestMask) - popcount(responseMask);
      peakInFlight = Math.max(peakInFlight, inFlight);
      for (let lane = 0; lane < timingTrace.lanesPerCore; ++lane) {
        const laneBit = 1 << lane;
        if (requestMask & laneBit) pendingByLane[lane].push(record.c);
        if (responseMask & laneBit) {
          const issueCycle = pendingByLane[lane].shift();
          if (issueCycle === undefined || record.c - issueCycle !== timingTrace.mulLatency) {
            throw new Error(`Cuper batch ${batch.index} PE${core} 的 FP64 IP req/resp 延迟异常`);
          }
        }
      }
      done += (record.d & bit) !== 0;
      stagedMask = acceptedNow ? validMask : 0;
    }
    const expectedInFlightDepth = timingTrace.lanesPerCore * Math.ceil(
        timingTrace.mulLatency / timingTrace.mulII);
    if (accepted !== batch.expectedBeats[core] || valid !== batch.expectedValid[core] ||
        padding !== batch.expectedBeats[core] * timingTrace.lanesPerCore - batch.expectedValid[core] ||
        xReads !== batch.expectedValid[core] || requests !== batch.expectedValid[core] ||
        responses !== batch.expectedValid[core] || done !== 1 || inFlight !== 0 ||
        peakInFlight > expectedInFlightDepth ||
        pendingByLane.some((pending) => pending.length !== 0) ||
        acceptedCycles.some((cycle, index) => index > 0 && cycle !== acceptedCycles[index - 1] + 1)) {
      throw new Error(`Cuper batch ${batch.index} PE${core} 没有保持 A beat II=1 或未完整排空 FMUL`);
    }
  }
  totalExpectedMultiply += batch.expectedMultiply;
}
if (totalExpectedMultiply !== timingTrace.expectedMultiply) {
  throw new Error("全部 Cuper batch 的 FMUL 期望数没有汇总到总计");
}

console.log(
    `[spmv-input-html-test] ${performancePath} input_cycles=${trace.cycles} ` +
    `mul_cycles=${timingTrace.records.length} batches=${timingTrace.batches.length} cores=16 lanes=128 PASS`);
