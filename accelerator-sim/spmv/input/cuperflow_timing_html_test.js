const fs = require("fs");

if (process.argv.length !== 3) {
  console.error("用法: node cuperflow_timing_html_test.js <cuperflow-timing.html>");
  process.exit(2);
}

const path = process.argv[2];
const html = fs.readFileSync(path, "utf8");
const match = html.match(/const timingTrace=(\{.*?\});\nconst t=/s);
if (match === null) throw new Error(`找不到 timingTrace: ${path}`);
const trace = JSON.parse(match[1]);

for (const marker of ["Cuperflow 时序与吞吐报告", "全局 wave/batch 时间线", "work 周期明细",
  "globalXReady / A AR", "8-lane X decoder", "物理 FMUL"]) {
  if (!html.includes(marker)) throw new Error(`报告缺少 ${marker}`);
}
if (trace.schedule !== "wave-major" || trace.pcCount !== 16 || trace.decoderLanes !== 8 ||
    trace.mulII !== 1 || trace.mulLatency < 1 || trace.sliceGroupCount <= 0 ||
    trace.waveCount !== Math.ceil(trace.sliceGroupCount / trace.pcCount) ||
    trace.batchCount <= 0 || !Array.isArray(trace.works) ||
    trace.works.length !== trace.waveCount * trace.batchCount) {
  throw new Error("Cuperflow timing 几何或 work 列表不符合冻结模型");
}
if (trace.totalCycles <= 0 || trace.totalABeats < trace.encodedABeats ||
    trace.physicalSlots < trace.usefulSlots ||
    trace.xSourceBeats <= 0 || trace.xWords < trace.xMarkers) {
  throw new Error("Cuperflow timing 汇总指标非法");
}

let previousEnd = -1;
let loadedGroups = 0;
for (const [index, work] of trace.works.entries()) {
  if (work.index !== index || work.wave !== Math.floor(index / trace.batchCount) ||
      work.batch !== index % trace.batchCount || work.start <= previousEnd ||
      work.done < work.start ||
      work.xReady < work.start || work.aRequest < work.xReady ||
      work.aBegin !== work.aRequest + 1 || work.aEnd < work.aBegin ||
      work.mulRequestBegin !== work.aBegin + 1 ||
      work.mulResponseBegin !== work.mulRequestBegin + trace.mulLatency ||
      work.mulResponseEnd !== work.mulRequestEnd + trace.mulLatency ||
      work.sliceGroups.length !== trace.pcCount || work.xLoaded.length !== trace.pcCount ||
      work.xElements.length !== trace.pcCount || work.xWords.length !== trace.pcCount ||
      work.xBeats.length !== trace.pcCount || work.xMarkers.length !== trace.pcCount ||
      work.xWriteCycles.length !== trace.pcCount ||
      work.aBeats.length !== trace.pcCount || work.usefulSlots.length !== trace.pcCount ||
      work.physicalSlots.length !== trace.pcCount) {
    throw new Error(`work ${index} 时序关系非法`);
  }
  for (let pc = 0; pc < trace.pcCount; ++pc) {
    const group = work.wave * trace.pcCount + pc;
    const active = group < trace.sliceGroupCount;
    const shouldLoadX = active && work.batch === 0;
    if (work.sliceGroups[pc] !== (active ? group : null) ||
        work.xLoaded[pc] !== shouldLoadX) {
      throw new Error(`work ${index} 没有使用 PC 独占的 wave sliceGroup 映射`);
    }
    if (!active && (work.xElements[pc] !== 0 || work.xWords[pc] !== 0 ||
        work.xBeats[pc] !== 0 || work.xMarkers[pc] !== 0 ||
        work.xWriteCycles[pc] !== 0 || work.aBeats[pc] !== 0)) {
      throw new Error(`work ${index} 尾 wave 空 PC 带有残留工作`);
    }
    if (shouldLoadX) ++loadedGroups;
  }
  previousEnd = work.done;
}
if (loadedGroups !== trace.sliceGroupCount || loadedGroups !== trace.xLoadedGroups ||
    previousEnd + 1 !== trace.totalCycles) {
  throw new Error("Cuperflow timing X load 或总周期汇总不一致");
}

console.log(`cuperflow timing html PASS: ${path} cycles=${trace.totalCycles} works=${trace.works.length}`);
