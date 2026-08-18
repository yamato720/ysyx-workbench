#pragma once

#include <string_view>

namespace accelerator_sim::spmv::encoding::cuperflow {

inline constexpr std::string_view kVectorHtmlPrefix = R"CUPERFLOW(<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Cuperflow X 向量布局</title>
<style>
:root{color-scheme:light;--bg:#edf1f2;--surface:#fff;--surface-2:#f5f8f8;--ink:#172126;--muted:#66757b;--line:#d1dade;--strong:#a8b5ba;--accent:#086c72;--accent-soft:#e2f0f0;--source:#775b19;--source-soft:#f5eddc;--encoded:#24684c;--encoded-soft:#e5f1e9;--bank:#67528f;--bank-soft:#eeeaf5;--padding:#746c61;--padding-soft:#eceae6}*{box-sizing:border-box}[hidden]{display:none!important}html,body{min-height:100%}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.45 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}button,select{font:inherit;color:inherit;letter-spacing:0}.mono{font-family:ui-monospace,SFMono-Regular,Consolas,"Liberation Mono",monospace;font-variant-numeric:tabular-nums}.app-header{position:sticky;top:0;z-index:20;background:var(--surface);border-bottom:1px solid var(--line)}.identity{min-height:67px;padding:10px 24px;display:flex;align-items:center;justify-content:space-between;gap:20px}.identity-main{display:flex;align-items:baseline;gap:12px;min-width:0}.identity h1{margin:0;font-size:20px;line-height:1.2;letter-spacing:0}.dataset{color:var(--accent);font-weight:750;overflow-wrap:anywhere}.report-tabs{display:flex;align-items:center;border:1px solid var(--strong);border-radius:6px;overflow:hidden;background:var(--surface)}.report-tabs a,.report-tabs span{height:31px;min-width:86px;padding:5px 12px;display:grid;place-items:center;color:var(--muted);text-decoration:none;font-size:12px;font-weight:700}.report-tabs a:hover,.report-tabs a:focus-visible{background:var(--accent-soft);color:var(--accent);outline:0}.report-tabs .active{background:var(--accent);color:#fff}.subhead{min-height:39px;padding:0 24px;display:flex;align-items:center;justify-content:space-between;gap:16px;border-top:1px solid #edf0f1;color:var(--muted);font-size:12px}.workspace{width:min(1460px,100%);margin:0 auto;padding:20px 24px 36px}.flow-band{display:grid;grid-template-columns:1fr auto 1fr auto 1.15fr auto 1.25fr;align-items:stretch;border-top:1px solid var(--line);border-bottom:1px solid var(--line);background:var(--surface);margin-bottom:18px}.flow-node{min-width:0;padding:11px 13px;border-left:4px solid var(--strong)}.flow-node.source{border-left-color:var(--source)}.flow-node.encoded{border-left-color:var(--encoded)}.flow-node.bank{border-left-color:var(--bank)}.flow-node strong,.flow-node span{display:block}.flow-node strong{font-size:13px}.flow-node span{margin-top:2px;color:var(--muted);font-size:11px;overflow-wrap:anywhere}.flow-arrow{width:28px;display:grid;place-items:center;color:var(--strong);font-size:17px}.view-head{display:flex;align-items:flex-end;justify-content:space-between;gap:22px;margin-bottom:15px}.view-head h2{margin:1px 0 0;font-size:20px;line-height:1.25;letter-spacing:0}.eyebrow{margin:0;color:var(--muted);font-size:11px;font-weight:750;text-transform:uppercase}.stat-strip{display:flex;align-items:stretch;justify-content:flex-end;flex-wrap:wrap;border-top:1px solid var(--line);border-bottom:1px solid var(--line);background:var(--surface)}.stat-item{min-width:116px;padding:7px 11px;border-left:3px solid var(--strong)}.stat-item.source{border-left-color:var(--source)}.stat-item.encoded{border-left-color:var(--encoded)}.stat-item.bank{border-left-color:var(--bank)}.stat-item strong,.stat-item span{display:block}.stat-item strong{font-size:15px;line-height:1.2;font-variant-numeric:tabular-nums}.stat-item span{margin-top:2px;color:var(--muted);font-size:11px}.batch-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(290px,1fr));gap:11px}.batch-card{width:100%;padding:13px;border:1px solid var(--line);border-radius:6px;background:var(--surface);text-align:left;cursor:pointer}.batch-card:hover,.batch-card:focus-visible{border-color:var(--accent);box-shadow:0 0 0 2px rgba(8,108,114,.12);outline:0}.card-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.card-title{font-size:16px;font-weight:750}.card-range,.card-meta{color:var(--muted);font-size:11px;font-variant-numeric:tabular-nums}.card-count{color:var(--encoded);font-weight:750}.packet-strip{height:8px;margin-top:13px;display:flex;border-radius:2px;overflow:hidden;background:var(--padding-soft)}.packet-valid{background:var(--encoded)}.packet-padding{background:var(--padding)}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:10px}.segmented{display:inline-flex;padding:2px;border:1px solid var(--strong);border-radius:6px;background:var(--surface)}.segmented button{height:30px;min-width:72px;padding:3px 10px;border:0;border-radius:3px;background:transparent;color:var(--muted);cursor:pointer}.segmented button.active{background:var(--accent);color:#fff}.page-controls{display:flex;align-items:center;gap:7px}.page-controls label{display:flex;align-items:center;gap:6px;color:var(--muted);font-size:12px}.page-controls select{height:32px;border:1px solid var(--strong);border-radius:4px;background:var(--surface);padding:3px 24px 3px 7px}.icon-button{width:32px;height:32px;padding:0;display:grid;place-items:center;border:1px solid var(--strong);border-radius:4px;background:var(--surface);font-size:17px;cursor:pointer}.icon-button:hover:not(:disabled),.icon-button:focus-visible{border-color:var(--accent);color:var(--accent);outline:0}.icon-button:disabled{opacity:.4;cursor:default}.page-info{min-width:160px;color:var(--muted);font-size:12px;text-align:center;font-variant-numeric:tabular-nums}.packet-scroll{overflow:auto;border:1px solid var(--line);background:var(--surface);scrollbar-gutter:stable}.packet-plane{min-width:1450px}.packet-row{display:grid;grid-template-columns:112px repeat(16,minmax(78px,1fr))}.packet-row+.packet-row{border-top:1px solid var(--line)}.packet-head{position:sticky;top:0;z-index:3;background:#e8edee}.packet-head>div{height:35px;display:grid;place-items:center;border-right:1px solid var(--line);color:#3c4a50;font-size:10px;font-weight:750}.packet-label{position:sticky;left:0;z-index:2;min-height:61px;padding:7px 9px;display:flex;flex-direction:column;justify-content:center;border-right:1px solid var(--line);background:#f1f5f5}.packet-label strong,.packet-label span{display:block}.packet-label strong{font-size:12px}.packet-label span{color:var(--muted);font-size:9px}.element-cell{height:61px;min-width:0;padding:4px 5px;border:0;border-right:1px solid var(--line);background:var(--encoded-soft);color:#1e583f;text-align:left;overflow:hidden;cursor:pointer}.element-cell.padding{background:var(--padding-soft);color:var(--padding)}.element-cell:hover,.element-cell:focus-visible{position:relative;z-index:1;box-shadow:inset 0 0 0 2px var(--accent);outline:0}.element-cell span{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.cell-primary{font-size:10px;font-weight:750}.cell-secondary{margin-top:2px;font-size:9px;opacity:.85}.cell-bank{margin-top:2px;color:var(--bank);font-size:9px;font-weight:700}.detail-layout{display:grid;grid-template-columns:minmax(330px,.9fr) minmax(500px,1.1fr);gap:18px}.detail-section h3{margin:0 0 7px;font-size:13px}.detail-table{width:100%;border-collapse:collapse;background:var(--surface);font-variant-numeric:tabular-nums}.detail-table th,.detail-table td{padding:7px 9px;border:1px solid var(--line);text-align:left;vertical-align:top;overflow-wrap:anywhere}.detail-table th{width:165px;background:#edf1f2;color:#45545a;font-size:11px}.detail-table td{font-size:12px}.conversion{display:grid;grid-template-columns:1fr auto 1fr;align-items:stretch;margin-bottom:16px}.conversion-node{padding:13px;border:1px solid var(--line);background:var(--surface)}.conversion-node.source{border-top:4px solid var(--source)}.conversion-node.encoded{border-top:4px solid var(--encoded)}.conversion-node strong,.conversion-node span{display:block}.conversion-node strong{font-size:16px;overflow-wrap:anywhere}.conversion-node span{margin-top:3px;color:var(--muted);font-size:11px}.conversion-arrow{width:42px;display:grid;place-items:center;color:var(--strong);font-size:19px}.replica-grid{display:grid;grid-template-columns:repeat(4,minmax(130px,1fr));gap:7px;margin-bottom:16px}.replica{padding:9px 10px;border:1px solid #d6cfea;border-top:4px solid var(--bank);border-radius:4px;background:var(--bank-soft)}.replica strong,.replica span{display:block}.replica span{margin-top:3px;color:#584672;font-size:11px}.empty-state{padding:40px 20px;border:1px solid var(--line);background:var(--surface);color:var(--muted);text-align:center}
@media(max-width:1000px){.flow-band{grid-template-columns:1fr}.flow-arrow{width:100%;height:20px}.view-head{align-items:flex-start;flex-direction:column}.stat-strip{justify-content:flex-start}.detail-layout{grid-template-columns:1fr}}
@media(max-width:680px){.identity{padding-left:12px;padding-right:12px;align-items:flex-start;flex-direction:column;gap:8px}.identity-main{align-items:flex-start;flex-direction:column;gap:2px}.report-tabs{width:100%;max-width:100%;min-width:0}.report-tabs a,.report-tabs span{min-width:0;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.subhead{padding:7px 12px;align-items:flex-start;flex-direction:column;gap:2px}.workspace{padding:14px 10px 28px}.flow-node{padding:8px 10px}.view-head{gap:11px}.view-head h2{font-size:18px}.stat-strip{width:100%}.stat-item{min-width:50%;padding:6px 8px}.batch-grid{grid-template-columns:minmax(0,1fr)}.batch-card{min-width:0;overflow:hidden}.card-head{flex-wrap:wrap}.card-count{overflow-wrap:anywhere}.toolbar{align-items:flex-start;flex-direction:column}.page-controls{width:100%;justify-content:space-between}.page-info{min-width:0;flex:1}.conversion{grid-template-columns:1fr}.conversion-arrow{width:100%;height:30px}.replica-grid{grid-template-columns:repeat(2,minmax(120px,1fr))}.detail-table th{width:120px}}
</style>
<style>
.packet-row{grid-template-columns:112px repeat(8,minmax(78px,1fr))}
.flow-band{grid-template-columns:1fr auto .8fr auto 1.2fr auto .8fr auto 1.15fr}
@media(max-width:1000px){.flow-band{grid-template-columns:1fr}}
</style>
</head>
<body>
<header class="app-header">
  <div class="identity">
    <div class="identity-main"><h1>Cuperflow X 向量布局</h1><span class="dataset" id="dataset"></span></div>
    <nav class="report-tabs" aria-label="编码报告"><a id="matrixReport">A Matrix</a><span class="active">X Vector</span><a id="timingReport">Timing</a></nav>
  </div>
  <div class="subhead"><span id="packageMeta"></span><span id="sourcePath"></span></div>
</header>
<main class="workspace">
  <div class="flow-band">
    <div class="flow-node source"><strong>PE_Param header</strong><span id="mapFlow"></span></div><div class="flow-arrow">→</div>
    <div class="flow-node"><strong>Batch start</strong><span>读取 SpElement_list_ptr[i]</span></div><div class="flow-arrow">→</div>
    <div class="flow-node encoded"><strong>HBM X range → local_X</strong><span id="coreFlow"></span></div><div class="flow-arrow">→</div>
    <div class="flow-node bank"><strong>Batch end</strong><span id="bankFlow"></span></div><div class="flow-arrow">→</div>
    <div class="flow-node"><strong>A × X</strong><span id="computeFlow"></span></div>
  </div>
  <section id="packageView">
    <div class="view-head"><div><p class="eyebrow">Vector Package</p><h2>Column Batch 总览</h2></div><div class="stat-strip" id="packageStats"></div></div>
    <div class="batch-grid" id="batchGrid"></div>
  </section>
  <section id="batchView" hidden>
    <div class="view-head"><div><p class="eyebrow">Column Batch</p><h2 id="batchTitle"></h2></div><div class="stat-strip" id="batchStats"></div></div>
    <div class="toolbar">
      <div class="segmented" id="cellMode" aria-label="元素显示模式"><button type="button" data-mode="value" class="active">数值</button><button type="button" data-mode="column">列号</button><button type="button" data-mode="bits">FP64 Bits</button></div>
      <div class="page-controls"><button class="icon-button" id="back" type="button" title="返回总览" aria-label="返回总览">←</button><label>每页 <select id="pageSize"><option>8</option><option selected>16</option><option>32</option><option>64</option></select></label><button class="icon-button" id="prev" type="button" title="上一页" aria-label="上一页">‹</button><span class="page-info" id="pageInfo"></span><button class="icon-button" id="next" type="button" title="下一页" aria-label="下一页">›</button></div>
    </div>
    <div class="packet-scroll"><div class="packet-plane" id="packetRows"></div></div>
  </section>
  <section id="elementView" hidden>
    <div class="view-head"><div><p class="eyebrow">Vector Element</p><h2 id="elementTitle"></h2></div><div class="stat-strip" id="elementStats"></div></div>
    <div class="conversion" id="conversion"></div>
    <div class="replica-grid" id="replicas"></div>
    <div class="detail-layout" id="elementDetails"></div>
  </section>
</main>
<script>const report=)CUPERFLOW";

inline constexpr std::string_view kVectorHtmlSuffix = R"CUPERFLOW(;
const I={seq:0,batch:1,beat:2,batchBeat:3,lane:4,column:5,localColumn:6,padding:7,source:8,fp64:9,bits:10,bank:11};
const ids=['dataset','matrixReport','packageMeta','sourcePath','mapFlow','coreFlow','bankFlow','computeFlow','packageView','packageStats','batchGrid','batchView','batchTitle','batchStats','cellMode','pageSize','back','prev','next','pageInfo','packetRows','elementView','elementTitle','elementStats','conversion','replicas','elementDetails'];
const E=Object.fromEntries(ids.map(id=>[id,document.getElementById(id)]));
E.timingReport=document.getElementById('timingReport');
const state={batch:null,page:0,mode:'value',element:null};
const elementsByBeat=new Map();
for(const element of report.elements){if(!elementsByBeat.has(element[I.beat]))elementsByBeat.set(element[I.beat],[]);elementsByBeat.get(element[I.beat]).push(element)}
const number=value=>Number(value).toLocaleString('zh-CN');
const bytes=value=>{const n=Number(value);return n<1024?n+' B':n<1048576?(n/1024).toFixed(1)+' KiB':(n/1048576).toFixed(2)+' MiB'};
const percentage=(numerator,denominator)=>denominator===0?'0.00%':(Number(numerator)/Number(denominator)*100).toFixed(2)+'%';
function make(tag,className,text){const node=document.createElement(tag);if(className)node.className=className;if(text!==undefined)node.textContent=text;return node}
function renderStats(container,items){container.replaceChildren();for(const [label,value,tone=''] of items){const item=make('div','stat-item'+(tone?' '+tone:''));item.append(make('strong','',value),make('span','',label));container.appendChild(item)}}
function detailTable(rows){const table=make('table','detail-table');for(const [label,value,className=''] of rows){const row=document.createElement('tr');row.append(make('th','',label),make('td',className,value===null?'—':String(value)));table.appendChild(row)}return table}
function show(id){for(const view of [E.packageView,E.batchView,E.elementView])view.hidden=view.id!==id;window.scrollTo({top:0,behavior:'auto'})}
function batchBounds(batch){const begin=report.batchPointers[batch],end=report.batchPointers[batch+1];return{begin,end,beats:end-begin,firstColumn:batch*report.config.columnsPerBatch,lastColumn:Math.min(report.stats.validElements,(batch+1)*report.config.columnsPerBatch)-1}}
function renderPackage(){show('packageView');const physicalSlots=report.stats.encodedPayloadBeats*report.config.lanesPerBeat;renderStats(E.packageStats,[['输入元素',number(report.stats.validElements),'source'],['编码模式',report.config.flexibleX?'灵活 X':'连续 X','encoded'],['逻辑列利用率',percentage(report.stats.encodedValueCount,report.stats.validElements),'encoded'],['有效 value lane',percentage(report.stats.encodedValueCount,physicalSlots),'encoded'],['token lane 利用率',percentage(report.stats.encodedWordCount,physicalSlots),'encoded'],['相对连续 beat',number(report.stats.encodedPayloadBeats)+' / '+number(report.stats.payloadBeats),'encoded'],['marker lane',percentage(report.stats.markerCount,report.stats.encodedWordCount),'encoded'],['HBM-owned ranges',number(report.stats.rangeCount),'encoded'],['最大 X range',number(report.stats.maximumRangeElements)+' elements'],['实际 X stream',number(report.stats.encodedPayloadBeats)+' beats','encoded'],['Address markers',number(report.stats.markerCount),'encoded'],['HBM allocation',bytes(report.stats.allocatedBytes)],['local_X copies / HBM',number(report.config.replicas),'bank']]);E.batchGrid.replaceChildren();for(let batch=0;batch<report.stats.batchCount;batch++){const bounds=batchBounds(batch),valid=Math.max(0,bounds.lastColumn-bounds.firstColumn+1),padding=bounds.beats*report.config.lanesPerBeat-valid,card=make('button','batch-card');card.type='button';const head=make('div','card-head'),title=make('div');title.append(make('div','card-title','Batch '+batch),make('div','card-range','C '+number(bounds.firstColumn)+' – '+number(bounds.lastColumn)));head.append(title,make('div','card-count',number(valid)+' values'));const strip=make('div','packet-strip'),validBar=make('span','packet-valid'),paddingBar=make('span','packet-padding');validBar.style.flex=String(valid);paddingBar.style.flex=String(padding);strip.append(validBar,paddingBar);card.append(head,strip,make('div','card-meta',number(bounds.beats)+' FP64 beats · '+number(padding)+' lane padding'));card.addEventListener('click',()=>{state.batch=batch;state.page=0;renderBatch()});E.batchGrid.appendChild(card)}}
function cell(element){const padding=element[I.padding],button=make('button','element-cell'+(padding?' padding':''));button.type='button';if(padding){button.append(make('span','cell-primary','Padding'),make('span','cell-secondary mono',element[I.bits]));button.disabled=true;return button}let primary,secondary;if(state.mode==='column'){primary='C '+element[I.column];secondary='Local '+element[I.localColumn]}else if(state.mode==='bits'){primary=element[I.bits];secondary='FP64'}else{primary=element[I.fp64];secondary='src '+element[I.source]}button.append(make('span','cell-primary'+(state.mode==='bits'?' mono':''),primary),make('span','cell-secondary',secondary),make('span','cell-bank','Bank '+element[I.bank]));button.title='Column '+element[I.column]+' · Lane '+element[I.lane]+' · Bank '+element[I.bank];button.addEventListener('click',()=>{state.element=element;renderElement()});return button}
function renderBatch(){show('batchView');const bounds=batchBounds(state.batch),pageSize=Number(E.pageSize.value),pages=Math.max(1,Math.ceil(bounds.beats/pageSize));state.page=Math.max(0,Math.min(state.page,pages-1));const begin=bounds.begin+state.page*pageSize,end=Math.min(bounds.end,begin+pageSize),valid=Math.max(0,bounds.lastColumn-bounds.firstColumn+1),padding=bounds.beats*report.config.lanesPerBeat-valid;E.batchTitle.textContent='Batch '+state.batch+' · FP64 平面';renderStats(E.batchStats,[['Column 范围',number(bounds.firstColumn)+' – '+number(bounds.lastColumn)],['Batch Beats',number(bounds.beats),'encoded'],['有效元素',number(valid),'source'],['Lane Padding',number(padding)],['每 Core replicas',number(report.config.replicas),'bank'],['Cyclic banks',number(report.config.partitionFactor),'bank']]);for(const button of E.cellMode.querySelectorAll('button'))button.classList.toggle('active',button.dataset.mode===state.mode);E.packetRows.replaceChildren();const header=make('div','packet-row packet-head');header.append(make('div','','HBM Beat'));for(let lane=0;lane<report.config.lanesPerBeat;lane++)header.append(make('div','','Lane '+lane));E.packetRows.appendChild(header);for(let beat=begin;beat<end;beat++){const row=make('div','packet-row'),label=make('div','packet-label');label.append(make('strong','',number(beat)),make('span','','Batch beat '+number(beat-bounds.begin)),make('span','','512 bit'));row.appendChild(label);const elements=(elementsByBeat.get(beat)||[]).slice().sort((a,b)=>a[I.lane]-b[I.lane]);for(const element of elements)row.appendChild(cell(element));E.packetRows.appendChild(row)}E.pageInfo.textContent=number(state.page+1)+' / '+number(pages)+' · Beats '+number(begin)+'–'+number(Math.max(begin,end-1));E.prev.disabled=state.page===0;E.next.disabled=state.page>=pages-1}
function renderElement(){
  show('elementView');
  const e=state.element;
  const owner=report.channelXRanges.findIndex(ranges=>ranges.some(range=>e[I.column]>=range[1]&&e[I.column]<range[1]+range[2]));
  E.elementTitle.textContent=e[I.padding]?'Padding element':'Column '+e[I.column];
  renderStats(E.elementStats,[
    ['Batch / beat',e[I.batch]+' / '+e[I.batchBeat]],
    ['HBM beat / lane',e[I.beat]+' / '+e[I.lane]],
    ['Local column',e[I.padding]?'—':number(e[I.localColumn])],
    ['Cyclic bank',e[I.padding]?'—':number(e[I.bank]),'bank'],
  ]);
  E.conversion.replaceChildren();
  const source=make('div','conversion-node source');
  const encoded=make('div','conversion-node encoded');
  source.append(make('strong','mono',e[I.padding]?'—':e[I.source]),make('span','','b.txt · FP64'));
  encoded.append(make('strong','mono',e[I.fp64]),make('span','',e[I.bits]+' · FP64'));
  E.conversion.append(source,make('div','conversion-arrow','→'),encoded);
  E.replicas.replaceChildren();
  if(!e[I.padding]){
    for(let replica=0;replica<report.config.replicas;replica++){
      const card=make('div','replica');
      card.append(
        make('strong','','local_X['+replica+']['+e[I.localColumn]+']'),
        make('span','','HBM '+owner+' · Bank '+e[I.bank]),
      );
      E.replicas.appendChild(card);
    }
  }
  const position=make('section','detail-section');
  const layout=make('section','detail-section');
  position.append(make('h3','','HBM 坐标'),detailTable([
    ['Global column',e[I.column]],
    ['Batch / local column',e[I.batch]+' / '+(e[I.localColumn]??'—')],
    ['Global / batch beat',e[I.beat]+' / '+e[I.batchBeat]],
    ['FP64 lane',e[I.lane]],
    ['FP64 bits',e[I.bits],'mono'],
  ]));
  layout.append(make('h3','','HBM 本地映射'),detailTable([
    ['X owner HBM',owner],
    ['Replica count',report.config.replicas],
    ['Cyclic partition',report.config.partitionFactor],
    ['Bank formula','localColumn % '+report.config.partitionFactor],
    ['Selected bank',e[I.padding]?'—':e[I.bank]],
  ]));
  E.elementDetails.replaceChildren(position,layout);
}
for(const button of E.cellMode.querySelectorAll('button'))button.addEventListener('click',()=>{state.mode=button.dataset.mode;renderBatch()});
E.pageSize.addEventListener('change',()=>{state.page=0;renderBatch()});E.back.addEventListener('click',renderPackage);E.prev.addEventListener('click',()=>{state.page--;renderBatch()});E.next.addEventListener('click',()=>{state.page++;renderBatch()});
E.dataset.textContent=report.dataset;E.matrixReport.href=report.matrixReport;E.timingReport.href=report.timingReport;E.packageMeta.textContent=number(report.stats.validElements)+' columns · '+number(report.stats.rangeCount)+' 个 HBM-owned X range · FP64 → FP64 · '+(report.config.flexibleX?'灵活 X':'连续 X')+' · '+number(report.stats.markerCount)+' markers';E.sourcePath.textContent=report.source;E.sourcePath.title=report.source;E.mapFlow.textContent='Batch_num / Row_num / Iteration_num / Column_num';E.coreFlow.textContent=report.config.coreCount+' HBM · 每路独占 ≤'+report.config.xRangeMaxElements+' 元素并写 '+report.config.replicas+' 份 local_X';E.bankFlow.textContent=(report.config.flexibleX?'ADDR marker → BRAM 地址 · ':'')+'读取 SpElement_list_ptr[i+1] · '+report.config.partitionFactor+' cyclic banks';E.computeFlow.textContent='消费当前 HBM range 的 Matrix_A_Stream 并乘加';
renderPackage();
</script>
</body>
</html>)CUPERFLOW";

}  // namespace accelerator_sim::spmv::encoding::cuperflow
