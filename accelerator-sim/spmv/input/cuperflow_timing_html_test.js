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

for (const marker of ["Cuperflow 时序与吞吐报告", "全作业 AXI 时间线", "PC 工作汇总",
  "X HBM / A 首个 R", "8-lane 连续 X 装填", "物理 FMUL 槽位"]) {
  if (!html.includes(marker)) throw new Error(`报告缺少 ${marker}`);
}
if (trace.schedule !== "per-pc-map-x-a" || trace.pcCount !== 16 || trace.xLoadLanes !== 8 ||
    trace.mulII !== 1 || trace.mulLatency < 1 || trace.sliceGroupCount <= 0 ||
    trace.batchCount <= 0 || !Array.isArray(trace.works) || trace.works.length !== 1) {
  throw new Error("Cuperflow timing 几何或 work 列表不符合冻结模型");
}
if (trace.totalCycles <= 0 || trace.totalABeats < trace.encodedABeats ||
    trace.physicalSlots < trace.usefulSlots ||
    trace.xSourceBeats <= 0 || trace.xWords < trace.xMarkers ||
    trace.xWriteCycles !== trace.xSourceBeats || trace.xMarkers !== 0) {
  throw new Error("Cuperflow timing 汇总指标非法");
}

const work = trace.works[0];
if (work.index !== 0 || work.done < work.start ||
    work.sliceGroups.length !== trace.pcCount || work.xLoaded.length !== trace.pcCount ||
      work.xElements.length !== trace.pcCount || work.xWords.length !== trace.pcCount ||
      work.xBeats.length !== trace.pcCount || work.xMarkers.length !== trace.pcCount ||
      work.xWriteCycles.length !== trace.pcCount ||
      work.aBeats.length !== trace.pcCount || work.usefulSlots.length !== trace.pcCount ||
      work.physicalSlots.length !== trace.pcCount) {
  throw new Error("Cuperflow 全作业工作汇总格式非法");
}
if (work.xLoaded.filter(Boolean).length === 0 ||
    work.xBeats.reduce((sum, beats) => sum + beats, 0) !== trace.xSourceBeats ||
    work.xWriteCycles.reduce((sum, cycles) => sum + cycles, 0) !== trace.xWriteCycles ||
    work.aBeats.reduce((sum, beats) => sum + beats, 0) !== trace.totalABeats ||
    work.usefulSlots.reduce((sum, slots) => sum + slots, 0) !== trace.usefulSlots ||
    work.physicalSlots.reduce((sum, slots) => sum + slots, 0) !== trace.physicalSlots) {
  throw new Error("Cuperflow 全作业 PC 累计统计不一致");
}
if (work.done + 1 !== trace.totalCycles || trace.xLoadedGroups !== trace.sliceGroupCount) {
  throw new Error("Cuperflow timing X load 或总周期汇总不一致");
}

console.log(`cuperflow timing html PASS: ${path} cycles=${trace.totalCycles} works=${trace.works.length}`);
