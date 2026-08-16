#include <branch-html.h>

#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

enum { BRANCH_HTML_DISASSEMBLY_SIZE = 160 };

typedef enum {
  BRANCH_KIND_CONDITIONAL,
  BRANCH_KIND_JAL,
  BRANCH_KIND_JALR,
  BRANCH_KIND_CALL,
  BRANCH_KIND_RETURN,
} BranchKind;

typedef struct {
  uint64_t sequence;
  uint64_t pc;
  uint64_t predicted_next_pc;
  uint64_t actual_next_pc;
  uint32_t instruction;
  BranchKind kind;
  bool dynamic_enabled;
  bool predicted_taken;
  bool actual_taken;
  bool direction_correct;
  bool target_checked;
  bool target_correct;
  bool redirect;
  char disassembly[BRANCH_HTML_DISASSEMBLY_SIZE];
} BranchHtmlRecord;

typedef struct {
  uint64_t pc;
  BranchKind kind;
  uint64_t count;
  uint64_t direction_correct;
  uint64_t direction_incorrect;
  uint64_t target_correct;
  uint64_t target_incorrect;
  uint64_t target_checked;
  uint64_t redirects;
} BranchHtmlSummary;

typedef struct {
  char *output_path;
  char *label;
  BranchHtmlRecord *records;
  size_t count;
  size_t capacity;
  size_t limit;
  uint64_t dropped;
  bool telemetry_available;
  bool dynamic_enabled;
  bool finished;
} BranchHtmlRecorder;

static BranchHtmlRecorder *global_recorder;

static char *duplicate_string(const char *value) {
  const char *source = value == NULL ? "" : value;
  const size_t size = strlen(source) + 1;
  char *copy = malloc(size);
  if (copy != NULL) memcpy(copy, source, size);
  return copy;
}

static BranchKind classify_branch(uint32_t instruction) {
  const uint32_t opcode = instruction & 0x7f;
  const uint32_t rd = (instruction >> 7) & 0x1f;
  const uint32_t rs1 = (instruction >> 15) & 0x1f;
  const uint32_t immediate = instruction >> 20;
  if (opcode == 0x63) return BRANCH_KIND_CONDITIONAL;
  if (opcode == 0x6f) return rd == 1 || rd == 5 ? BRANCH_KIND_CALL : BRANCH_KIND_JAL;
  if (opcode == 0x67) {
    if (rd == 0 && (rs1 == 1 || rs1 == 5) && immediate == 0)
      return BRANCH_KIND_RETURN;
    return rd == 1 || rd == 5 ? BRANCH_KIND_CALL : BRANCH_KIND_JALR;
  }
  return BRANCH_KIND_JAL;
}

static const char *kind_name(BranchKind kind) {
  static const char *names[] = {
    "条件分支", "JAL", "JALR", "调用", "返回",
  };
  return kind <= BRANCH_KIND_RETURN ? names[kind] : "控制流";
}

static bool is_control_transfer(uint32_t instruction) {
  const uint32_t opcode = instruction & 0x7f;
  return opcode == 0x63 || opcode == 0x6f || opcode == 0x67;
}

static bool reserve_record(BranchHtmlRecorder *recorder) {
  if (recorder->count < recorder->capacity) return true;
  size_t capacity = recorder->capacity == 0 ? 1024 : recorder->capacity * 2;
  if (capacity > recorder->limit) capacity = recorder->limit;
  if (capacity <= recorder->capacity) return false;
  BranchHtmlRecord *records = realloc(recorder->records, capacity * sizeof(*records));
  if (records == NULL) return false;
  recorder->records = records;
  recorder->capacity = capacity;
  return true;
}

static int compare_record_pc(const void *left, const void *right) {
  const BranchHtmlRecord *a = left;
  const BranchHtmlRecord *b = right;
  if (a->pc < b->pc) return -1;
  if (a->pc > b->pc) return 1;
  if (a->sequence < b->sequence) return -1;
  if (a->sequence > b->sequence) return 1;
  return 0;
}

static void write_html_string(FILE *output, const char *value) {
  const char *text = value == NULL ? "" : value;
  for (; *text != '\0'; text++) {
    switch (*text) {
      case '&': fputs("&amp;", output); break;
      case '<': fputs("&lt;", output); break;
      case '>': fputs("&gt;", output); break;
      case '"': fputs("&quot;", output); break;
      case '\'': fputs("&#39;", output); break;
      default: fputc(*text, output); break;
    }
  }
}

static void write_json_string(FILE *output, const char *value) {
  const unsigned char *text = (const unsigned char *)(value == NULL ? "" : value);
  fputc('"', output);
  for (; *text != '\0'; text++) {
    switch (*text) {
      case '"': fputs("\\\"", output); break;
      case '\\': fputs("\\\\", output); break;
      case '\n': fputs("\\n", output); break;
      case '\r': fputs("\\r", output); break;
      case '\t': fputs("\\t", output); break;
      case '<': fputs("\\u003c", output); break;
      case '>': fputs("\\u003e", output); break;
      case '&': fputs("\\u0026", output); break;
      default:
        if (*text < 0x20) fprintf(output, "\\u%04x", *text);
        else fputc(*text, output);
        break;
    }
  }
  fputc('"', output);
}

static BranchHtmlRecorder *branch_html_create(const char *output_path, const char *label) {
  BranchHtmlRecorder *recorder = calloc(1, sizeof(*recorder));
  if (recorder == NULL) return NULL;
  recorder->output_path = duplicate_string(output_path);
  recorder->label = duplicate_string(label == NULL || label[0] == '\0' ? "nemu" : label);
  recorder->limit = BRANCH_HTML_DEFAULT_LIMIT;
  recorder->telemetry_available = true;
  if (recorder->output_path == NULL || recorder->label == NULL) {
    free(recorder->output_path);
    free(recorder->label);
    free(recorder);
    return NULL;
  }
  return recorder;
}

static int write_atomic_document(BranchHtmlRecorder *recorder) {
  const size_t temporary_size = strlen(recorder->output_path) + 32;
  char *temporary = malloc(temporary_size);
  if (temporary == NULL) return -1;
  snprintf(temporary, temporary_size, "%s.tmp.%ld", recorder->output_path, (long)getpid());
  FILE *output = fopen(temporary, "w");
  if (output == NULL) {
    free(temporary);
    return -1;
  }

  BranchHtmlRecord *sorted = NULL;
  BranchHtmlSummary *summaries = NULL;
  size_t summary_count = 0;
  if (recorder->count != 0) {
    sorted = malloc(recorder->count * sizeof(*sorted));
    summaries = calloc(recorder->count, sizeof(*summaries));
    if (sorted == NULL || summaries == NULL) {
      free(sorted);
      free(summaries);
      fclose(output);
      unlink(temporary);
      free(temporary);
      return -1;
    }
    memcpy(sorted, recorder->records, recorder->count * sizeof(*sorted));
    qsort(sorted, recorder->count, sizeof(*sorted), compare_record_pc);
    for (size_t index = 0; index < recorder->count; index++) {
      BranchHtmlRecord *record = &sorted[index];
      BranchHtmlSummary *summary = summary_count == 0 ||
          summaries[summary_count - 1].pc != record->pc
          ? &summaries[summary_count++] : &summaries[summary_count - 1];
      if (summary->count == 0) {
        summary->pc = record->pc;
        summary->kind = record->kind;
      }
      summary->count++;
      if (record->direction_correct) summary->direction_correct++;
      else summary->direction_incorrect++;
      if (record->target_checked) {
        summary->target_checked++;
        if (record->target_correct) summary->target_correct++;
        else summary->target_incorrect++;
      }
      if (record->redirect) summary->redirects++;
    }
  }

  uint64_t direction_correct = 0;
  uint64_t target_correct = 0;
  uint64_t target_checked = 0;
  uint64_t redirects = 0;
  for (size_t index = 0; index < recorder->count; index++) {
    direction_correct += recorder->records[index].direction_correct;
    target_correct += recorder->records[index].target_correct;
    target_checked += recorder->records[index].target_checked;
    redirects += recorder->records[index].redirect;
  }

  fputs(
      "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
      "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
      "<title>NPC 分支预测报告</title><style>"
      ":root{color-scheme:light;--bg:#f4f6f8;--ink:#18222d;--muted:#63707d;--line:#d8dee5;--panel:#fff;--accent:#176b87;--good:#287d54;--bad:#b42318}"
      "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.5 system-ui,sans-serif}"
      "header{padding:22px max(20px,calc((100vw - 1240px)/2));background:#fff;border-bottom:1px solid var(--line)}"
      "h1{margin:0;font-size:24px;letter-spacing:0}.subtitle{margin-top:5px;color:var(--muted)}"
      "main{max-width:1240px;margin:0 auto;padding:18px 20px 34px}section{margin:0 0 24px}h2{font-size:17px;margin:0 0 10px}"
      ".metrics{display:grid;grid-template-columns:repeat(5,minmax(145px,1fr));gap:8px}.metric{min-width:0;padding:12px 13px;background:var(--panel);border:1px solid var(--line);border-radius:6px}"
      ".metric span,.metric small{display:block;color:var(--muted)}.metric strong{display:block;margin:5px 0 1px;font-size:22px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.metric small{font-size:12px}"
      ".note{padding:10px 12px;border-left:3px solid var(--accent);background:#fff;color:var(--muted)}"
      ".table-wrap{overflow:auto;border:1px solid var(--line);background:#fff}table{width:100%;border-collapse:collapse;white-space:nowrap}th,td{padding:8px 10px;border-bottom:1px solid #e7ebef;text-align:right}th{background:#edf1f4;color:#46525e;font-size:12px}th:first-child,td:first-child{text-align:left}tbody tr:hover{background:#f7fafc}.good{color:var(--good);font-weight:650}.bad{color:var(--bad);font-weight:650}.muted{color:var(--muted)}"
      ".tools{display:flex;gap:10px;align-items:center;justify-content:space-between;margin-bottom:8px}.tools input,.tools select{padding:6px 8px;border:1px solid #aeb9c4;border-radius:4px;background:#fff;font:inherit}.tools input{min-width:260px}"
      ".pager{display:flex;gap:8px;align-items:center;justify-content:flex-end;margin-top:8px}.pager button{border:1px solid #82919f;border-radius:4px;padding:5px 9px;background:#fff;font:inherit;cursor:pointer}.pager button:disabled{opacity:.45;cursor:default}"
      ".actions{display:flex;gap:10px;margin-top:12px}.actions a{display:inline-flex;padding:7px 10px;border:1px solid #82919f;border-radius:4px;color:#155f78;background:#fff;text-decoration:none}footer{max-width:1240px;margin:auto;padding:0 20px 24px;color:var(--muted);font-size:12px}"
      "@media(max-width:900px){.metrics{grid-template-columns:repeat(3,1fr)}}@media(max-width:520px){header,main,footer{padding-left:12px;padding-right:12px}.metrics{grid-template-columns:repeat(2,1fr)}.metric strong{font-size:18px}.tools{align-items:flex-start;flex-direction:column}.tools input{min-width:0;width:100%}}"
      "</style></head><body><header><h1>NPC 分支预测报告</h1><div class=\"subtitle\"><span>", output);
  write_html_string(output, recorder->label);
  fputs("</span><span> · </span><span>控制流提交事件</span></div></header><main>", output);
  fputs("<section><h2>预测总览</h2><div class=\"metrics\">", output);
  fprintf(output, "<div class=\"metric\"><span>控制流指令</span><strong>%" PRIu64 "</strong><small>已记录事件</small></div>", recorder->count);
  fprintf(output, "<div class=\"metric\"><span>动态预测</span><strong>%s</strong><small>%s</small></div>",
          recorder->dynamic_enabled ? "启用" : "未启用",
          recorder->telemetry_available ? "当前构造" : "当前硬件不可观测");
  fprintf(output, "<div class=\"metric\"><span>方向命中率</span><strong>%.2f%%</strong><small>%" PRIu64 " / %" PRIu64 "</small></div>",
          recorder->count == 0 ? 0.0 : 100.0 * (double)direction_correct / (double)recorder->count,
          direction_correct, recorder->count);
  fprintf(output, "<div class=\"metric\"><span>目标命中率</span><strong>%.2f%%</strong><small>%" PRIu64 " / %" PRIu64 "</small></div>",
          target_checked == 0 ? 0.0 : 100.0 * (double)target_correct / (double)target_checked,
          target_correct, target_checked);
  fprintf(output, "<div class=\"metric\"><span>重定向/flush</span><strong>%" PRIu64 "</strong><small>预测 next-PC 不一致</small></div>", redirects);
  fputs("</div>", output);
  if (!recorder->telemetry_available) {
    fputs("<p class=\"note\">当前运行没有可观测的 NPC 分支预测遥测；页面不把缺失数据当成预测失败。</p>", output);
  } else if (recorder->count == 0) {
    fputs("<p class=\"note\">本次运行没有提交可观测的控制流指令。</p>", output);
  } else if (!recorder->dynamic_enabled) {
    fputs("<p class=\"note\">当前构造未启用动态分支预测；统计的是顺序路径与已有静态控制流预测的结果。</p>", output);
  }
  fputs("</section><section><h2>按 PC 汇总</h2><div class=\"table-wrap\"><table><thead><tr><th>PC</th><th>类型</th><th>次数</th><th>方向命中</th><th>方向失败</th><th>目标命中</th><th>目标失败</th><th>重定向</th></tr></thead><tbody>", output);
  for (size_t index = 0; index < summary_count; index++) {
    const BranchHtmlSummary *summary = &summaries[index];
    fprintf(output, "<tr><td>0x%016" PRIx64 "</td><td>", summary->pc);
    write_html_string(output, kind_name(summary->kind));
    fprintf(output, "</td><td>%" PRIu64 "</td><td class=\"good\">%" PRIu64 "</td><td class=\"bad\">%" PRIu64 "</td><td>%" PRIu64 "</td><td>%" PRIu64 "</td><td>%" PRIu64 "</td></tr>",
            summary->count, summary->direction_correct, summary->direction_incorrect,
            summary->target_correct, summary->target_incorrect, summary->redirects);
  }
  if (summary_count == 0) fputs("<tr><td colspan=\"8\" class=\"muted\">没有可显示的控制流事件。</td></tr>", output);
  fputs("</tbody></table></div></section><section><div class=\"tools\"><h2>逐条事件</h2><div><input id=\"search\" placeholder=\"搜索 PC、机器码或反汇编\"><select id=\"pageSize\"><option>50</option><option>100</option><option>250</option></select></div></div><div class=\"table-wrap\"><table><thead><tr><th>#</th><th>PC</th><th>机器码</th><th>反汇编</th><th>预测 next-PC</th><th>实际 next-PC</th><th>方向</th><th>目标</th><th>结果</th></tr></thead><tbody id=\"events\"></tbody></table></div><div class=\"pager\"><button id=\"prev\">上一页</button><span id=\"page\"></span><button id=\"next\">下一页</button></div></section><section class=\"actions\"><a href=\"performance.html\">返回性能主页</a></section></main><footer>方向比较 taken/not-taken；目标比较实际跳转地址；重定向表示预测 next-PC 与实际 next-PC 不一致。</footer>", output);
  fputs("<script>const trace={dynamic:", output);
  fputs(recorder->dynamic_enabled ? "true" : "false", output);
  fputs(",records:[", output);
  for (size_t index = 0; index < recorder->count; index++) {
    const BranchHtmlRecord *record = &recorder->records[index];
    if (index != 0) fputc(',', output);
    fprintf(output, "{n:%" PRIu64 ",pc:%" PRIu64 ",inst:%" PRIu32 ",pred:%" PRIu64 ",actual:%" PRIu64 ",pt:%s", record->sequence, record->pc, record->instruction, record->predicted_next_pc, record->actual_next_pc, record->predicted_taken ? "true" : "false");
    fprintf(output, ",at:%s,dir:%s,tc:%s,checked:%s,redirect:%s,kind:", record->actual_taken ? "true" : "false", record->direction_correct ? "true" : "false", record->target_correct ? "true" : "false", record->target_checked ? "true" : "false", record->redirect ? "true" : "false");
    write_json_string(output, kind_name(record->kind));
    fputs(",asm:", output); write_json_string(output, record->disassembly); fputc('}', output);
  }
  fputs("]};let page=0,filtered=trace.records;const rows=document.querySelector('#events'),search=document.querySelector('#search'),size=document.querySelector('#pageSize');function hex(v){return '0x'+v.toString(16).padStart(16,'0')}function render(){const count=+size.value,pages=Math.max(1,Math.ceil(filtered.length/count));page=Math.min(page,pages-1);rows.textContent='';for(const r of filtered.slice(page*count,(page+1)*count)){const tr=document.createElement('tr');const values=[r.n,hex(r.pc),'0x'+r.inst.toString(16).padStart(8,'0'),r.asm,hex(r.pred),hex(r.actual),r.dir?'命中':'失败',r.checked?(r.tc?'命中':'失败'):'不适用',r.redirect?'REDIRECT':'命中'];values.forEach((value,index)=>{const td=document.createElement('td');td.textContent=value;if((index===6&&r.dir)||(index===7&&r.checked&&r.tc))td.className='good';if((index===6&&!r.dir)||(index===7&&r.checked&&!r.tc))td.className='bad';tr.appendChild(td)});rows.appendChild(tr)}if(!rows.children.length){const tr=document.createElement('tr'),td=document.createElement('td');td.colSpan=9;td.className='muted';td.textContent='没有匹配的控制流事件。';tr.appendChild(td);rows.appendChild(tr)}document.querySelector('#page').textContent=`第 ${page+1}/${pages} 页，共 ${filtered.length.toLocaleString()} 条`;document.querySelector('#prev').disabled=page===0;document.querySelector('#next').disabled=page>=pages-1}function filter(){const query=search.value.trim().toLowerCase();filtered=query?trace.records.filter(r=>String(r.n).includes(query)||hex(r.pc).includes(query)||('0x'+r.inst.toString(16)).includes(query)||r.asm.toLowerCase().includes(query)):trace.records;page=0;render()}search.addEventListener('input',filter);size.addEventListener('change',()=>{page=0;render()});document.querySelector('#prev').onclick=()=>{page--;render()};document.querySelector('#next').onclick=()=>{page++;render()};render();</script></body></html>", output);

  int status = ferror(output) || fclose(output) != 0 ? -1 : 0;
  if (status == 0 && rename(temporary, recorder->output_path) != 0) status = -1;
  if (status != 0) unlink(temporary);
  free(sorted);
  free(summaries);
  free(temporary);
  return status;
}

void npc_branch_html_init(void) {
  if (global_recorder != NULL) return;
  const char *directory = getenv("NEMU_RUNTIME_OUTPUT_DIR");
  const char *label = getenv("NEMU_RUNTIME_LABEL");
  const char *base = directory == NULL || directory[0] == '\0' ? "." : directory;
  const size_t size = strlen(base) + sizeof("/branch.html");
  char *path = malloc(size);
  if (path == NULL) return;
  snprintf(path, size, "%s/branch.html", base);
  global_recorder = branch_html_create(path, label);
  free(path);
  if (global_recorder == NULL)
    fprintf(stderr, "无法初始化 NPC 分支 HTML 记录器\n");
}

void npc_branch_html_set_mode(bool telemetry_available, bool dynamic_enabled) {
  if (global_recorder == NULL) npc_branch_html_init();
  if (global_recorder == NULL) return;
  global_recorder->telemetry_available = telemetry_available;
  global_recorder->dynamic_enabled = dynamic_enabled;
}

void npc_branch_html_record(
    uint64_t sequence,
    uint64_t pc,
    uint32_t instruction,
    const char *disassembly,
    uint64_t predicted_next_pc,
    uint64_t actual_next_pc,
    bool dynamic_enabled) {
  if (!is_control_transfer(instruction)) return;
  if (global_recorder == NULL) npc_branch_html_init();
  if (global_recorder == NULL || global_recorder->finished) return;
  global_recorder->dynamic_enabled |= dynamic_enabled;
  if (global_recorder->count >= global_recorder->limit || !reserve_record(global_recorder)) {
    global_recorder->dropped++;
    return;
  }
  BranchHtmlRecord *record = &global_recorder->records[global_recorder->count++];
  const uint64_t fallthrough = pc + 4;
  record->sequence = sequence;
  record->pc = pc;
  record->predicted_next_pc = predicted_next_pc;
  record->actual_next_pc = actual_next_pc;
  record->instruction = instruction;
  record->kind = classify_branch(instruction);
  record->dynamic_enabled = dynamic_enabled;
  record->actual_taken = record->kind == BRANCH_KIND_CONDITIONAL
      ? actual_next_pc != fallthrough : true;
  record->predicted_taken = predicted_next_pc != fallthrough;
  record->direction_correct = record->predicted_taken == record->actual_taken;
  record->target_checked = record->actual_taken && record->predicted_taken;
  record->target_correct = record->target_checked && predicted_next_pc == actual_next_pc;
  record->redirect = predicted_next_pc != actual_next_pc;
  snprintf(record->disassembly, sizeof(record->disassembly), "%s",
           disassembly == NULL ? "" : disassembly);
}

void npc_branch_html_finalize(void) {
  if (global_recorder == NULL) npc_branch_html_init();
  if (global_recorder == NULL || global_recorder->finished) return;
  if (write_atomic_document(global_recorder) == 0) {
    global_recorder->finished = true;
    printf("NEMU 分支预测 HTML：%s\n", global_recorder->output_path);
  } else {
    fprintf(stderr, "写入 NEMU 分支预测 HTML 失败：%s（%s）\n",
            global_recorder->output_path, strerror(errno));
  }
}
