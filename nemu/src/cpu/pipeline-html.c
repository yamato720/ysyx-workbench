#include <pipeline-html.h>

#include <errno.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

enum { PIPELINE_HTML_DISASSEMBLY_SIZE = 160 };

typedef struct {
  uint64_t sequence;
  uint64_t pc;
  uint64_t commit_cycle;
  uint64_t stage[PIPELINE_HTML_STAGE_COUNT];
  uint32_t instruction;
  char disassembly[PIPELINE_HTML_DISASSEMBLY_SIZE];
} PipelineHtmlRecord;

struct PipelineHtmlRecorder {
  char *output_path;
  char *instructions_output_path;
  char *label;
  PipelineHtmlRecord *records;
  size_t count;
  size_t capacity;
  size_t limit;
  uint64_t dropped;
  bool finished;
  bool instructions_finished;
};

static PipelineHtmlRecorder *global_recorder;

static char *duplicate_string(const char *value) {
  const char *source = value == NULL ? "" : value;
  size_t size = strlen(source) + 1;
  char *copy = malloc(size);
  if (copy != NULL) memcpy(copy, source, size);
  return copy;
}

static char *sibling_path(const char *path, const char *name) {
  const char *slash = strrchr(path, '/');
  const size_t prefix = slash == NULL ? 0 : (size_t)(slash - path + 1);
  char *result = malloc(prefix + strlen(name) + 1);
  if (result == NULL) return NULL;
  if (prefix != 0) memcpy(result, path, prefix);
  strcpy(result + prefix, name);
  return result;
}

void pipeline_html_compute_intervals(
    uint64_t commit_cycle,
    const uint64_t stage_cycles[PIPELINE_HTML_STAGE_COUNT],
    PipelineHtmlInterval intervals[PIPELINE_HTML_STAGE_COUNT]) {
  uint64_t total = 0;
  for (size_t stage = 0; stage < PIPELINE_HTML_STAGE_COUNT; stage++) {
    if (UINT64_MAX - total < stage_cycles[stage]) total = UINT64_MAX;
    else total += stage_cycles[stage];
  }

  uint64_t cursor = 0;
  if (total != 0 && commit_cycle >= total - 1) cursor = commit_cycle - total + 1;
  for (size_t stage = 0; stage < PIPELINE_HTML_STAGE_COUNT; stage++) {
    intervals[stage].start = cursor;
    if (stage_cycles[stage] == 0) {
      intervals[stage].end = cursor;
    } else if (UINT64_MAX - cursor < stage_cycles[stage] - 1) {
      intervals[stage].end = UINT64_MAX;
      cursor = UINT64_MAX;
    } else {
      intervals[stage].end = cursor + stage_cycles[stage] - 1;
      cursor += stage_cycles[stage];
    }
  }
}

PipelineHtmlRecorder *pipeline_html_create(
    const char *output_path, const char *label, size_t limit) {
  if (output_path == NULL || output_path[0] == '\0') return NULL;
  PipelineHtmlRecorder *recorder = calloc(1, sizeof(*recorder));
  if (recorder == NULL) return NULL;
  recorder->output_path = duplicate_string(output_path);
  recorder->instructions_output_path = sibling_path(output_path, "instructions.html");
  recorder->label = duplicate_string(label == NULL || label[0] == '\0' ? "nemu" : label);
  recorder->limit = limit;
  if (recorder->output_path == NULL || recorder->instructions_output_path == NULL || recorder->label == NULL) {
    pipeline_html_destroy(recorder);
    return NULL;
  }
  return recorder;
}

static bool reserve_record(PipelineHtmlRecorder *recorder) {
  if (recorder->count < recorder->capacity) return true;
  size_t capacity = recorder->capacity == 0 ? 1024 : recorder->capacity * 2;
  if (capacity > recorder->limit) capacity = recorder->limit;
  if (capacity <= recorder->capacity) return false;
  PipelineHtmlRecord *records = realloc(recorder->records, capacity * sizeof(*records));
  if (records == NULL) return false;
  recorder->records = records;
  recorder->capacity = capacity;
  return true;
}

void pipeline_html_record(
    PipelineHtmlRecorder *recorder,
    uint64_t sequence,
    uint64_t pc,
    uint32_t instruction,
    const char *disassembly,
    uint64_t commit_cycle,
    const uint64_t stage_cycles[PIPELINE_HTML_STAGE_COUNT]) {
  if (recorder == NULL || recorder->finished) return;
  if (recorder->count >= recorder->limit || !reserve_record(recorder)) {
    recorder->dropped++;
    return;
  }

  PipelineHtmlRecord *record = &recorder->records[recorder->count++];
  record->sequence = sequence;
  record->pc = pc;
  record->instruction = instruction;
  record->commit_cycle = commit_cycle;
  memcpy(record->stage, stage_cycles, sizeof(record->stage));
  snprintf(record->disassembly, sizeof(record->disassembly), "%s",
           disassembly == NULL ? "" : disassembly);
}

static void write_json_string(FILE *output, const char *value) {
  fputc('"', output);
  for (const unsigned char *cursor = (const unsigned char *)value; *cursor != '\0'; cursor++) {
    switch (*cursor) {
      case '"': fputs("\\\"", output); break;
      case '\\': fputs("\\\\", output); break;
      case '\b': fputs("\\b", output); break;
      case '\f': fputs("\\f", output); break;
      case '\n': fputs("\\n", output); break;
      case '\r': fputs("\\r", output); break;
      case '\t': fputs("\\t", output); break;
      case '<': fputs("\\u003c", output); break;
      case '>': fputs("\\u003e", output); break;
      case '&': fputs("\\u0026", output); break;
      default:
        if (*cursor < 0x20) fprintf(output, "\\u%04x", *cursor);
        else fputc(*cursor, output);
    }
  }
  fputc('"', output);
}

static int write_document(
    FILE *output,
    const PipelineHtmlRecorder *recorder,
    const uint64_t stalls[PIPELINE_HTML_STAGE_COUNT]) {
  static const char *prefix =
      "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
      "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
      "<title>NEMU 流水线时间线</title><style>"
      ":root{color-scheme:light;--bg:#f6f7f9;--ink:#17202a;--muted:#65717e;--line:#d7dce2;"
      "--col-seq:64px;--col-pc:150px;--col-inst:112px;--col-asm:290px;--timeline-width:640px;"
      "--if:#16697a;--id:#8f5d18;--ex:#8c2f39;--mem:#386641;--wb:#5a3d8a}*{box-sizing:border-box}"
      "body{height:100vh;margin:0;overflow:hidden;display:flex;flex-direction:column;background:var(--bg);color:var(--ink);font:14px/1.45 system-ui,sans-serif}"
      "header{padding:20px 24px 14px;background:#fff;border-bottom:1px solid var(--line)}.titlebar{display:flex;justify-content:space-between;gap:12px;align-items:center}h1{font-size:22px;margin:0 0 6px}.home{color:#155f78;text-decoration:none;border:1px solid #82919f;border-radius:4px;padding:6px 9px;white-space:nowrap}"
      ".summary,.legend,.controls{display:flex;gap:14px;flex-wrap:wrap;align-items:center}.summary{color:var(--muted)}"
      ".warning{margin-top:10px;padding:8px 10px;border-left:4px solid #b42318;background:#fff1f0;color:#7a271a}"
      ".legend{padding:10px 24px;background:#fff;border-bottom:1px solid var(--line)}.key:before{content:'';display:inline-block;width:12px;height:12px;margin-right:5px;background:var(--c);vertical-align:-1px}"
      ".controls{padding:12px 24px}.controls input[type=search]{min-width:260px;padding:7px 9px;border:1px solid #aeb6bf;border-radius:4px}"
      "button,select,input{font:inherit}button{padding:6px 10px;border:1px solid #9aa4af;border-radius:4px;background:#fff;cursor:pointer}button:disabled{opacity:.45}"
      ".viewport{flex:1;min-height:0;overflow:auto;scrollbar-gutter:stable;scrollbar-color:#788592 #e4e8ec;border-top:1px solid var(--line);border-bottom:1px solid var(--line);background:#fff;overscroll-behavior:contain}"
      ".viewport::-webkit-scrollbar{width:14px;height:14px}.viewport::-webkit-scrollbar-track{background:#e4e8ec}.viewport::-webkit-scrollbar-thumb{background:#788592;border:3px solid #e4e8ec;border-radius:7px}"
      ".hscroll{position:relative;flex:0 0 18px;background:#e4e8ec;border-bottom:1px solid var(--line);cursor:pointer;touch-action:none}.hscroll[hidden]{display:none}.hscroll-thumb{position:absolute;top:3px;left:0;height:12px;min-width:40px;border-radius:6px;background:#788592;cursor:grab}.hscroll-thumb:active{cursor:grabbing;background:#596875}.hscroll:focus-visible{outline:2px solid #16697a;outline-offset:-2px}"
      ".head,.row{display:grid;grid-template-columns:var(--col-seq) var(--col-pc) var(--col-inst) var(--col-asm) var(--timeline-width);width:calc(var(--col-seq) + var(--col-pc) + var(--col-inst) + var(--col-asm) + var(--timeline-width))}"
      ".head{position:sticky;top:0;z-index:3;background:#eef1f4;font-weight:650}.head>div,.meta{padding:7px 8px;border-right:1px solid var(--line)}"
      ".row{min-height:38px;border-top:1px solid #eceff2}.row:hover{background:#f8fbff}.meta{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;align-content:center}"
      ".head>div:nth-child(-n+4),.row>.meta:nth-child(-n+4){position:sticky;z-index:2;background:#fff}"
      ".head>div:nth-child(-n+4){z-index:4;background:#eef1f4}.row:hover>.meta:nth-child(-n+4){background:#f8fbff}"
      ".head>div:nth-child(1),.row>.meta:nth-child(1){left:0}.head>div:nth-child(2),.row>.meta:nth-child(2){left:var(--col-seq)}"
      ".head>div:nth-child(3),.row>.meta:nth-child(3){left:calc(var(--col-seq) + var(--col-pc))}"
      ".head>div:nth-child(4),.row>.meta:nth-child(4){left:calc(var(--col-seq) + var(--col-pc) + var(--col-inst));box-shadow:5px 0 7px -6px #59636e}"
      ".timeline{position:relative;min-height:37px;background-image:linear-gradient(to right,rgba(70,80,90,.12) 1px,transparent 1px);background-size:var(--cell) 100%}"
      ".stage{position:absolute;top:5px;height:27px;color:#fff;padding:4px 5px;overflow:hidden;white-space:nowrap;font-size:12px;background:var(--c)}"
      ".axis{color:var(--muted);font-size:12px}.footer{padding:12px 24px;display:flex;gap:12px;align-items:center}.empty{padding:30px;color:var(--muted)}"
      "@media(max-width:700px){:root{--col-seq:48px;--col-pc:120px;--col-inst:96px;--col-asm:220px}header,.legend,.controls,.footer{padding-left:12px;padding-right:12px}.controls input[type=search]{min-width:100%;width:100%}}"
      "@media(max-width:520px){:root{--col-seq:38px;--col-pc:92px;--col-inst:82px;--col-asm:150px}.head>div,.meta{padding-left:5px;padding-right:5px}}"
      "</style></head><body><header><div class=\"titlebar\"><h1>NEMU 已提交指令流水时间线</h1><a class=\"home\" href=\"performance.html\">返回性能主页</a></div><div class=\"summary\" id=\"summary\"></div><div class=\"warning\" id=\"warning\" hidden></div></header>"
      "<div class=\"legend\"><span class=\"key\" style=\"--c:var(--if)\">IF</span><span class=\"key\" style=\"--c:var(--id)\">ID</span><span class=\"key\" style=\"--c:var(--ex)\">EX</span><span class=\"key\" style=\"--c:var(--mem)\">MEM</span><span class=\"key\" style=\"--c:var(--wb)\">WB</span></div>"
      "<div class=\"controls\"><input id=\"search\" type=\"search\" placeholder=\"搜索 PC、机器码或反汇编\"><label>每页 <select id=\"pageSize\"><option>50</option><option selected>100</option><option>250</option><option>500</option></select></label><label>周期宽度 <input id=\"zoom\" type=\"range\" min=\"4\" max=\"28\" value=\"14\"></label></div>"
      "<div class=\"viewport\"><div class=\"head\"><div>#</div><div>PC</div><div>机器码</div><div>反汇编</div><div class=\"axis\">周期时间线</div></div><div id=\"rows\"></div></div><div class=\"hscroll\" id=\"hscroll\" role=\"scrollbar\" aria-label=\"时间线横向滚动\" aria-orientation=\"horizontal\" tabindex=\"0\"><div class=\"hscroll-thumb\" id=\"hscrollThumb\"></div></div>"
      "<div class=\"footer\"><button id=\"prev\">上一页</button><span id=\"page\"></span><button id=\"next\">下一页</button></div><script>const trace=";
  fputs(prefix, output);
  fputs("{\"label\":", output);
  write_json_string(output, recorder->label);
  fprintf(output,
          ",\"captured\":%zu,\"dropped\":%" PRIu64
          ",\"stalls\":[%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 "]"
          ",\"records\":[",
          recorder->count, recorder->dropped,
          stalls[0], stalls[1], stalls[2], stalls[3], stalls[4]);

  for (size_t index = 0; index < recorder->count; index++) {
    const PipelineHtmlRecord *record = &recorder->records[index];
    PipelineHtmlInterval intervals[PIPELINE_HTML_STAGE_COUNT];
    pipeline_html_compute_intervals(record->commit_cycle, record->stage, intervals);
    if (index != 0) fputc(',', output);
    fprintf(output,
            "{\"n\":%" PRIu64 ",\"pc\":\"0x%016" PRIx64
            "\",\"inst\":\"0x%08" PRIx32 "\",\"asm\":",
            record->sequence, record->pc, record->instruction);
    write_json_string(output, record->disassembly);
    fprintf(output, ",\"commit\":%" PRIu64 ",\"d\":[", record->commit_cycle);
    for (size_t stage = 0; stage < PIPELINE_HTML_STAGE_COUNT; stage++) {
      fprintf(output, "%s%" PRIu64, stage == 0 ? "" : ",", record->stage[stage]);
    }
    fputs("],\"s\":[", output);
    for (size_t stage = 0; stage < PIPELINE_HTML_STAGE_COUNT; stage++) {
      fprintf(output, "%s%" PRIu64, stage == 0 ? "" : ",", intervals[stage].start);
    }
    fputs("]}", output);
  }

  static const char *suffix =
      "]};const names=['IF','ID','EX','MEM','WB'];const colors=['var(--if)','var(--id)','var(--ex)','var(--mem)','var(--wb)'];"
      "let page=0,filtered=trace.records;const viewport=document.querySelector('.viewport'),hscroll=document.querySelector('#hscroll'),hscrollThumb=document.querySelector('#hscrollThumb'),rows=document.querySelector('#rows'),search=document.querySelector('#search'),size=document.querySelector('#pageSize'),zoom=document.querySelector('#zoom');"
      "const total=trace.captured+trace.dropped;document.querySelector('#summary').textContent=`${trace.label} · 提交 ${total.toLocaleString()} 条 · 记录 ${trace.captured.toLocaleString()} 条 · 背压周期 IF/ID/EX/MEM=${trace.stalls.slice(0,4).join('/')} · flush=${trace.stalls[4]}`;"
      "if(trace.dropped){const w=document.querySelector('#warning');w.hidden=false;w.textContent=`已达到 200000 条记录上限，后续 ${trace.dropped.toLocaleString()} 条提交未写入时间线。统计仍包含全部提交。`}"
      "function render(){const count=+size.value,pages=Math.max(1,Math.ceil(filtered.length/count));page=Math.min(page,pages-1);const part=filtered.slice(page*count,(page+1)*count);rows.textContent='';"
      "if(!part.length){viewport.style.setProperty('--timeline-width','640px');rows.innerHTML='<div class=\"empty\">没有匹配的已提交指令。</div>';}else{const min=Math.min(...part.map(r=>r.s[0])),max=Math.max(...part.map(r=>r.commit));const cell=+zoom.value,timelineWidth=Math.max(640,(max-min+1)*cell);viewport.style.setProperty('--timeline-width',timelineWidth+'px');"
      "for(const r of part){const row=document.createElement('div');row.className='row';for(const value of [r.n,r.pc,r.inst,r.asm]){const m=document.createElement('div');m.className='meta';m.textContent=value;m.title=String(value);row.appendChild(m);}"
      "const line=document.createElement('div');line.className='timeline';line.style.setProperty('--cell',cell+'px');"
      "r.d.forEach((duration,i)=>{if(!duration)return;const b=document.createElement('div');b.className='stage';b.style.setProperty('--c',colors[i]);b.style.left=((r.s[i]-min)*cell)+'px';b.style.width=Math.max(2,duration*cell)+'px';b.textContent=names[i];const end=r.s[i]+duration-1;b.title=`${names[i]}：周期 ${r.s[i]}–${end}，驻留 ${duration} 周期；提交周期 ${r.commit}`;line.appendChild(b)});row.appendChild(line);rows.appendChild(row);}}"
      "document.querySelector('#page').textContent=`第 ${page+1}/${pages} 页，共 ${filtered.length.toLocaleString()} 条`;document.querySelector('#prev').disabled=page===0;document.querySelector('#next').disabled=page>=pages-1;requestAnimationFrame(syncHorizontalScrollbar);}"
      "function filter(){const q=search.value.trim().toLowerCase();filtered=q?trace.records.filter(r=>r.pc.toLowerCase().includes(q)||r.inst.toLowerCase().includes(q)||r.asm.toLowerCase().includes(q)):trace.records;page=0;render()}"
      "function horizontalScrollMax(){return Math.max(0,viewport.scrollWidth-viewport.offsetWidth)}function syncHorizontalScrollbar(){const max=horizontalScrollMax(),width=hscroll.clientWidth,thumbWidth=max?Math.max(40,width*viewport.offsetWidth/viewport.scrollWidth):width,travel=Math.max(0,width-thumbWidth);hscroll.hidden=max===0;hscrollThumb.style.width=thumbWidth+'px';hscrollThumb.style.transform=`translateX(${max?viewport.scrollLeft/max*travel:0}px)`;hscroll.setAttribute('aria-valuemin','0');hscroll.setAttribute('aria-valuemax',String(max));hscroll.setAttribute('aria-valuenow',String(Math.round(viewport.scrollLeft)));}"
      "let drag=null;hscrollThumb.addEventListener('pointerdown',event=>{event.preventDefault();drag={x:event.clientX,left:viewport.scrollLeft};hscrollThumb.setPointerCapture(event.pointerId)});hscrollThumb.addEventListener('pointermove',event=>{if(!drag)return;const max=horizontalScrollMax(),travel=hscroll.clientWidth-hscrollThumb.offsetWidth;viewport.scrollLeft=drag.left+(event.clientX-drag.x)*max/Math.max(1,travel)});hscrollThumb.addEventListener('pointerup',event=>{drag=null;hscrollThumb.releasePointerCapture(event.pointerId)});"
      "hscroll.addEventListener('pointerdown',event=>{if(event.target!==hscroll)return;const rect=hscroll.getBoundingClientRect(),max=horizontalScrollMax(),travel=rect.width-hscrollThumb.offsetWidth;viewport.scrollLeft=((event.clientX-rect.left-hscrollThumb.offsetWidth/2)/Math.max(1,travel))*max});hscroll.addEventListener('keydown',event=>{const max=horizontalScrollMax(),step=Math.max(40,viewport.offsetWidth*.8);if(event.key==='ArrowLeft')viewport.scrollLeft-=40;else if(event.key==='ArrowRight')viewport.scrollLeft+=40;else if(event.key==='PageUp')viewport.scrollLeft-=step;else if(event.key==='PageDown')viewport.scrollLeft+=step;else if(event.key==='Home')viewport.scrollLeft=0;else if(event.key==='End')viewport.scrollLeft=max;else return;event.preventDefault()});"
      "viewport.addEventListener('scroll',syncHorizontalScrollbar);window.addEventListener('resize',syncHorizontalScrollbar);search.addEventListener('input',filter);size.addEventListener('change',()=>{page=0;render()});zoom.addEventListener('input',render);document.querySelector('#prev').onclick=()=>{page--;render()};document.querySelector('#next').onclick=()=>{page++;render()};render();</script></body></html>";
  fputs(suffix, output);
  return ferror(output) ? -1 : 0;
}

static int write_instruction_document(FILE *output, const PipelineHtmlRecorder *recorder) {
  static const char *prefix =
      "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
      "<title>NEMU 逐指令明细</title><style>:root{color-scheme:light;--bg:#f4f6f8;--ink:#18222d;--muted:#63707d;--line:#d8dee5;--panel:#fff}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.5 system-ui,sans-serif}"
      "header{position:sticky;top:0;z-index:5;padding:18px max(16px,calc((100vw - 1380px)/2));background:#fff;border-bottom:1px solid var(--line)}.titlebar{display:flex;justify-content:space-between;gap:12px;align-items:center}h1{margin:0;font-size:22px}.home{color:#155f78;text-decoration:none;border:1px solid #82919f;border-radius:4px;padding:6px 9px;white-space:nowrap}.summary{margin-top:5px;color:var(--muted)}.warning{margin-top:9px;padding:7px 9px;border-left:4px solid #b42318;background:#fff1f0;color:#7a271a}"
      "main{max-width:1380px;margin:auto;padding:16px}.tools{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-bottom:10px}input,select,button{font:inherit}input[type=search]{min-width:310px;padding:7px 9px;border:1px solid #aeb6bf;border-radius:4px}button{padding:6px 10px;border:1px solid #9aa4af;border-radius:4px;background:#fff;cursor:pointer}button:disabled{opacity:.45}.table-wrap{overflow:auto;max-height:calc(100vh - 205px);background:#fff;border:1px solid var(--line)}table{width:100%;border-collapse:collapse;white-space:nowrap}th,td{padding:7px 9px;border-bottom:1px solid #e7ebef;text-align:right}th{position:sticky;top:0;background:#edf1f4;color:#46525e;font-size:12px}th:nth-child(-n+4),td:nth-child(-n+4){text-align:left}tbody tr:hover{background:#f7fafc}.pager{display:flex;gap:10px;align-items:center;margin-top:10px}.empty{text-align:left;color:var(--muted);padding:24px}"
      "@media(max-width:600px){header,main{padding-left:10px;padding-right:10px}.titlebar{align-items:flex-start}.home{font-size:12px}input[type=search]{min-width:100%;width:100%}.table-wrap{max-height:calc(100vh - 245px)}}"
      "</style></head><body><header><div class=\"titlebar\"><h1>NEMU 逐指令明细</h1><a class=\"home\" href=\"performance.html\">返回性能主页</a></div><div class=\"summary\" id=\"summary\"></div><div class=\"warning\" id=\"warning\" hidden></div></header><main><div class=\"tools\"><input id=\"search\" type=\"search\" placeholder=\"搜索序号、PC、机器码或反汇编\"><label>每页 <select id=\"pageSize\"><option>50</option><option selected>100</option><option>250</option><option>500</option></select></label></div><div class=\"table-wrap\"><table><thead><tr><th>#</th><th>PC</th><th>机器码</th><th>反汇编</th><th>提交周期</th><th>IF</th><th>ID</th><th>EX</th><th>MEM</th><th>WB</th><th>总延迟</th></tr></thead><tbody id=\"rows\"></tbody></table></div><div class=\"pager\"><button id=\"prev\">上一页</button><span id=\"page\"></span><button id=\"next\">下一页</button></div><script>const trace=";
  fputs(prefix, output);
  fputs("{\"label\":", output);
  write_json_string(output, recorder->label);
  fprintf(output, ",\"captured\":%zu,\"dropped\":%" PRIu64 ",\"records\":[",
          recorder->count, recorder->dropped);
  for (size_t index = 0; index < recorder->count; index++) {
    const PipelineHtmlRecord *record = &recorder->records[index];
    PipelineHtmlInterval intervals[PIPELINE_HTML_STAGE_COUNT];
    pipeline_html_compute_intervals(record->commit_cycle, record->stage, intervals);
    if (index != 0) fputc(',', output);
    fprintf(output, "{\"n\":%" PRIu64 ",\"pc\":\"0x%016" PRIx64
                    "\",\"inst\":\"0x%08" PRIx32 "\",\"asm\":",
            record->sequence, record->pc, record->instruction);
    write_json_string(output, record->disassembly);
    fprintf(output, ",\"commit\":%" PRIu64 ",\"d\":[", record->commit_cycle);
    for (size_t stage = 0; stage < PIPELINE_HTML_STAGE_COUNT; stage++) {
      fprintf(output, "%s%" PRIu64, stage == 0 ? "" : ",", record->stage[stage]);
    }
    fputs("],\"s\":[", output);
    for (size_t stage = 0; stage < PIPELINE_HTML_STAGE_COUNT; stage++) {
      fprintf(output, "%s%" PRIu64, stage == 0 ? "" : ",", intervals[stage].start);
    }
    fputs("]}", output);
  }
  static const char *suffix =
      "]};let page=0,filtered=trace.records;const rows=document.querySelector('#rows'),search=document.querySelector('#search'),size=document.querySelector('#pageSize');const total=trace.captured+trace.dropped;document.querySelector('#summary').textContent=`${trace.label} · 提交 ${total.toLocaleString()} 条 · 逐条记录 ${trace.captured.toLocaleString()} 条`;if(trace.dropped){const warning=document.querySelector('#warning');warning.hidden=false;warning.textContent=`已达到 200000 条记录上限，后续 ${trace.dropped.toLocaleString()} 条提交仅计入汇总。`}function render(){const count=+size.value,pages=Math.max(1,Math.ceil(filtered.length/count));page=Math.min(page,pages-1);rows.textContent='';for(const r of filtered.slice(page*count,(page+1)*count)){const tr=document.createElement('tr'),values=[r.n,r.pc,r.inst,r.asm,r.commit,...r.d,r.d.reduce((a,b)=>a+b,0)];values.forEach((value,index)=>{const td=document.createElement('td');td.textContent=value;if(index>=5&&index<=9){const stage=index-5,end=r.s[stage]+r.d[stage]-1;td.title=`周期 ${r.s[stage]}–${end}`}tr.appendChild(td)});rows.appendChild(tr)}if(!rows.children.length){const td=document.createElement('td');td.colSpan=11;td.className='empty';td.textContent='没有匹配的已提交指令。';const tr=document.createElement('tr');tr.appendChild(td);rows.appendChild(tr)}document.querySelector('#page').textContent=`第 ${page+1}/${pages} 页，共 ${filtered.length.toLocaleString()} 条`;document.querySelector('#prev').disabled=page===0;document.querySelector('#next').disabled=page>=pages-1}function filter(){const query=search.value.trim().toLowerCase();filtered=query?trace.records.filter(r=>String(r.n).includes(query)||r.pc.toLowerCase().includes(query)||r.inst.toLowerCase().includes(query)||r.asm.toLowerCase().includes(query)):trace.records;page=0;render()}search.addEventListener('input',filter);size.addEventListener('change',()=>{page=0;render()});document.querySelector('#prev').onclick=()=>{page--;render()};document.querySelector('#next').onclick=()=>{page++;render()};render();</script></main></body></html>";
  fputs(suffix, output);
  return ferror(output) ? -1 : 0;
}

static int write_atomic(
    const char *output_path,
    int (*writer)(FILE *, const PipelineHtmlRecorder *),
    const PipelineHtmlRecorder *recorder) {
  size_t temporary_size = strlen(output_path) + 48;
  char *temporary = malloc(temporary_size);
  if (temporary == NULL) return -1;
  snprintf(temporary, temporary_size, "%s.tmp.%ld", output_path, (long)getpid());
  FILE *output = fopen(temporary, "w");
  if (output == NULL) {
    free(temporary);
    return -1;
  }
  int status = writer(output, recorder);
  if (fclose(output) != 0) status = -1;
  if (status == 0 && rename(temporary, output_path) != 0) status = -1;
  if (status != 0) unlink(temporary);
  free(temporary);
  return status;
}

int pipeline_html_write_instructions(PipelineHtmlRecorder *recorder) {
  if (recorder == NULL) return -1;
  if (recorder->instructions_finished) return 0;
  const int status = write_atomic(recorder->instructions_output_path,
                                  write_instruction_document, recorder);
  if (status == 0) recorder->instructions_finished = true;
  return status;
}

int pipeline_html_finish(
    PipelineHtmlRecorder *recorder,
    const uint64_t stalls[PIPELINE_HTML_STAGE_COUNT]) {
  if (recorder == NULL) return -1;
  if (recorder->finished) return 0;

  size_t temporary_size = strlen(recorder->output_path) + 48;
  char *temporary = malloc(temporary_size);
  if (temporary == NULL) return -1;
  snprintf(temporary, temporary_size, "%s.tmp.%ld", recorder->output_path, (long)getpid());
  FILE *output = fopen(temporary, "w");
  if (output == NULL) {
    free(temporary);
    return -1;
  }
  int status = write_document(output, recorder, stalls);
  if (fclose(output) != 0) status = -1;
  if (status == 0 && rename(temporary, recorder->output_path) != 0) status = -1;
  if (status != 0) unlink(temporary);
  free(temporary);
  if (status == 0) recorder->finished = true;
  return status;
}

void pipeline_html_destroy(PipelineHtmlRecorder *recorder) {
  if (recorder == NULL) return;
  free(recorder->output_path);
  free(recorder->instructions_output_path);
  free(recorder->label);
  free(recorder->records);
  free(recorder);
}

size_t pipeline_html_captured(const PipelineHtmlRecorder *recorder) {
  return recorder == NULL ? 0 : recorder->count;
}

uint64_t pipeline_html_dropped(const PipelineHtmlRecorder *recorder) {
  return recorder == NULL ? 0 : recorder->dropped;
}

void npc_pipeline_html_init(void) {
  if (global_recorder != NULL) return;
  const char *directory = getenv("NEMU_RUNTIME_OUTPUT_DIR");
  const char *label = getenv("NEMU_RUNTIME_LABEL");
  const char *base = directory == NULL || directory[0] == '\0' ? "." : directory;
  size_t size = strlen(base) + sizeof("/pipeline.html");
  char *path = malloc(size);
  if (path == NULL) return;
  snprintf(path, size, "%s/pipeline.html", base);
  global_recorder = pipeline_html_create(path, label, PIPELINE_HTML_DEFAULT_LIMIT);
  free(path);
  if (global_recorder == NULL) {
    fprintf(stderr, "无法初始化 NEMU 流水线 HTML 记录器\n");
  }
}

void npc_pipeline_html_record(
    uint64_t sequence,
    uint64_t pc,
    uint32_t instruction,
    const char *disassembly,
    uint64_t commit_cycle,
    const uint64_t stage_cycles[PIPELINE_HTML_STAGE_COUNT]) {
  if (global_recorder == NULL) npc_pipeline_html_init();
  pipeline_html_record(global_recorder, sequence, pc, instruction,
                       disassembly, commit_cycle, stage_cycles);
}

void npc_pipeline_html_finalize(
    const uint64_t stalls[PIPELINE_HTML_STAGE_COUNT],
    bool write_pipeline_html) {
  if (global_recorder == NULL) npc_pipeline_html_init();
  if (global_recorder == NULL) return;
  if (pipeline_html_write_instructions(global_recorder) == 0) {
    printf("NEMU 逐指令 HTML：%s\n", global_recorder->instructions_output_path);
  } else {
    fprintf(stderr, "写入 NEMU 逐指令 HTML 失败：%s（%s）\n",
            global_recorder->instructions_output_path, strerror(errno));
  }
  if (!write_pipeline_html || global_recorder->finished) return;
  if (pipeline_html_finish(global_recorder, stalls) == 0) {
    printf("NEMU 流水线 HTML：%s\n", global_recorder->output_path);
  } else {
    fprintf(stderr, "写入 NEMU 流水线 HTML 失败：%s（%s）\n",
            global_recorder->output_path, strerror(errno));
  }
}
