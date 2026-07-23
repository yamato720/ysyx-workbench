#include <performance-html.h>

#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char *stage_names[PERFORMANCE_HTML_STAGE_COUNT] = {
  "IF", "ID", "EX", "MEM", "WB",
};

static const char *stall_names[PERFORMANCE_HTML_STALL_COUNT] = {
  "IF AXI", "ID RAW / backpressure", "EX backpressure", "MEM backpressure", "redirect / flush",
};

static void write_html_string(FILE *output, const char *value) {
  const unsigned char *cursor = (const unsigned char *)(value == NULL ? "" : value);
  for (; *cursor != '\0'; cursor++) {
    switch (*cursor) {
      case '&': fputs("&amp;", output); break;
      case '<': fputs("&lt;", output); break;
      case '>': fputs("&gt;", output); break;
      case '"': fputs("&quot;", output); break;
      case '\'': fputs("&#39;", output); break;
      default: fputc(*cursor, output);
    }
  }
}

static double ratio(uint64_t numerator, uint64_t denominator) {
  return denominator == 0 ? 0.0 : (double)numerator / (double)denominator;
}

static uint64_t sum_stages(const uint64_t stages[PERFORMANCE_HTML_STAGE_COUNT]) {
  uint64_t total = 0;
  for (size_t index = 0; index < PERFORMANCE_HTML_STAGE_COUNT; index++) total += stages[index];
  return total;
}

static const char *row_group(const char *name) {
  if (name == NULL) return "summary";
  if (strncmp(name, "load", 4) == 0) return "load";
  if (strncmp(name, "store", 5) == 0) return "store";
  if (strncmp(name, "m.", 2) == 0 || strcmp(name, "m(all)") == 0) return "multiply";
  return "summary";
}

static void write_metric(FILE *output, const char *label, const char *value, const char *detail) {
  fputs("<div class=\"metric\"><span>", output);
  write_html_string(output, label);
  fputs("</span><strong>", output);
  write_html_string(output, value);
  fputs("</strong><small>", output);
  write_html_string(output, detail);
  fputs("</small></div>", output);
}

static void format_u64(char *buffer, size_t size, uint64_t value) {
  snprintf(buffer, size, "%'" PRIu64, value);
}

static int write_document(FILE *output, const PerformanceHtmlReport *report) {
  static const char *prefix =
      "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
      "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
      "<title>NEMU 性能报告</title><style>"
      ":root{color-scheme:light;--bg:#f4f6f8;--ink:#18222d;--muted:#63707d;--line:#d8dee5;--panel:#fff;"
      "--if:#147582;--id:#a36716;--ex:#a23b45;--mem:#35714a;--wb:#67489a;--accent:#176b87}"
      "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.5 system-ui,sans-serif}"
      "header{padding:22px max(20px,calc((100vw - 1240px)/2));background:#fff;border-bottom:1px solid var(--line)}"
      "h1{margin:0;font-size:24px;letter-spacing:0}.subtitle{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:5px;color:var(--muted)}"
      ".status{padding:2px 8px;border-radius:4px;font-weight:650}.good{color:#17653a;background:#e6f5eb}.bad{color:#8b1e24;background:#fdebec}.quit{color:#6a5215;background:#fff3cf}"
      "main{max-width:1240px;margin:0 auto;padding:18px 20px 34px}section{margin:0 0 24px}h2{font-size:17px;margin:0 0 10px}"
      ".metrics{display:grid;grid-template-columns:repeat(6,minmax(130px,1fr));gap:8px}.metric{min-width:0;padding:12px 13px;background:var(--panel);border:1px solid var(--line);border-radius:6px}"
      ".metric span,.metric small{display:block;color:var(--muted)}.metric strong{display:block;margin:5px 0 1px;font-size:22px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
      ".metric small{font-size:12px}.band{background:#fff;border-top:1px solid var(--line);border-bottom:1px solid var(--line)}.band-inner{max-width:1240px;margin:auto;padding:18px 20px}"
      ".pipeline-meta{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px}.badge{padding:4px 8px;border:1px solid #b8c2cc;border-radius:4px;background:#f8fafb}"
      ".stall-grid{display:grid;grid-template-columns:210px 1fr 110px;gap:7px 10px;align-items:center}.track{height:12px;background:#e7ebef;border-radius:3px;overflow:hidden}.fill{height:100%;background:var(--c);min-width:2px}"
      ".stage-stack{display:flex;height:34px;margin-top:16px;overflow:hidden;border-radius:4px;background:#e7ebef}.stage-stack span{display:flex;align-items:center;justify-content:center;min-width:28px;color:#fff;background:var(--c);font-size:12px;font-weight:650}"
      ".stage-legend{display:flex;gap:14px;flex-wrap:wrap;margin-top:8px;color:var(--muted)}.stage-legend i{display:inline-block;width:10px;height:10px;margin-right:5px;background:var(--c)}"
      ".table-tools{display:flex;gap:10px;align-items:center;justify-content:space-between;margin-bottom:8px}.segments{display:flex;gap:2px;padding:2px;background:#e4e8ec;border-radius:5px}"
      "button{border:0;border-radius:3px;padding:6px 10px;background:transparent;font:inherit;cursor:pointer}button.active{background:#fff;color:#0f6179;box-shadow:0 1px 3px #aab4bf}"
      ".table-wrap{overflow:auto;border:1px solid var(--line);background:#fff}table{width:100%;border-collapse:collapse;white-space:nowrap}th,td{padding:8px 10px;border-bottom:1px solid #e7ebef;text-align:right}"
      "th{position:sticky;top:0;background:#edf1f4;color:#46525e;font-size:12px}th:first-child,td:first-child{text-align:left}tbody tr:hover{background:#f7fafc}.muted{color:var(--muted)}"
      ".last{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:1px;background:var(--line);border:1px solid var(--line)}.last div{padding:10px 12px;background:#fff}.last span{display:block;color:var(--muted);font-size:12px}.last strong{display:block;margin-top:3px;overflow:hidden;text-overflow:ellipsis}"
      ".actions{display:flex;gap:10px;margin-top:12px}.actions a{display:inline-flex;padding:7px 10px;border:1px solid #82919f;border-radius:4px;color:#155f78;background:#fff;text-decoration:none}"
      "footer{max-width:1240px;margin:auto;padding:0 20px 24px;color:var(--muted);font-size:12px}"
      "@media(max-width:900px){.metrics{grid-template-columns:repeat(3,1fr)}.stall-grid{grid-template-columns:150px 1fr 80px}.last{grid-template-columns:repeat(2,1fr)}}"
      "@media(max-width:520px){header,main,.band-inner,footer{padding-left:12px;padding-right:12px}.metrics{grid-template-columns:repeat(2,1fr)}.metric strong{font-size:18px}.stall-grid{grid-template-columns:110px 1fr 66px}.last{grid-template-columns:1fr}.table-tools{align-items:flex-start;flex-direction:column}}"
      "</style></head><body><header><h1>NEMU 性能报告</h1><div class=\"subtitle\"><span>";
  fputs(prefix, output);
  write_html_string(output, report->label);
  fputs("</span><span>·</span><span>", output);
  write_html_string(output, report->mode);
  fputs("</span><span class=\"status ", output);
  switch (report->outcome) {
    case PERFORMANCE_HTML_OUTCOME_GOOD: fputs("good", output); break;
    case PERFORMANCE_HTML_OUTCOME_QUIT: fputs("quit", output); break;
    default: fputs("bad", output); break;
  }
  fputs("\">", output);
  write_html_string(output, report->outcome_text);
  fputs("</span></div></header><main><section><h2>执行总览</h2><div class=\"metrics\">", output);

  char value[96];
  char detail[96];
  format_u64(value, sizeof(value), report->cycles);
  write_metric(output, "硬件周期", value, "cycles");
  format_u64(value, sizeof(value), report->commits);
  write_metric(output, "提交指令", value, "commits");
  if (report->cycles != 0 && report->commits != 0) {
    snprintf(value, sizeof(value), "%.4f", ratio(report->cycles, report->commits));
    write_metric(output, "CPI", value, "cycles / commit");
    const double ipc = ratio(report->commits, report->cycles);
    snprintf(value, sizeof(value), "%.4f", ipc);
    write_metric(output, "IPC", value, "commits / cycle");
    snprintf(value, sizeof(value), "%.2f", ipc * report->clock_mhz);
    snprintf(detail, sizeof(detail), "@ %.0f MHz", report->clock_mhz);
    write_metric(output, "模型吞吐", value, "MIPS");
    snprintf(value, sizeof(value), "%.3f ms", (double)report->cycles / (report->clock_mhz * 1000.0));
    write_metric(output, "模型时间", value, detail);
  } else {
    for (int index = 0; index < 4; index++) {
      static const char *labels[] = {"CPI", "IPC", "模型吞吐", "模型时间"};
      write_metric(output, labels[index], "N/A", "尚无提交");
    }
  }
  fputs("</div></section><section><h2>宿主运行</h2><div class=\"metrics\">", output);
  snprintf(value, sizeof(value), "%.0f MHz", report->clock_mhz);
  write_metric(output, "模型时钟", value, "统计换算频率");
  snprintf(value, sizeof(value), "%.3f ms", (double)report->host_time_us / 1000.0);
  write_metric(output, "宿主耗时", value, "wall-clock execution");
  format_u64(value, sizeof(value), report->guest_instructions);
  write_metric(output, "NEMU 指令", value, "guest instructions");
  if (report->host_time_us != 0) {
    snprintf(value, sizeof(value), "%.2f M/s", (double)report->guest_instructions / (double)report->host_time_us);
    write_metric(output, "仿真速度", value, "guest instructions / second");
  } else {
    write_metric(output, "仿真速度", "N/A", "小于 1 us");
  }
  fputs("</div></section></main><div class=\"band\"><div class=\"band-inner\"><section><h2>流水与停顿</h2><div class=\"pipeline-meta\">", output);

  const bool pipeline = (report->pipeline_features & 0x1u) != 0;
  fprintf(output, "<span class=\"badge\">流水线 %s</span>", pipeline ? "启用" : "关闭");
  if (pipeline) {
    fprintf(output, "<span class=\"badge\">ID 前递 %s</span>",
            (report->pipeline_features & 0x2u) != 0 ? "启用" : "关闭");
    fprintf(output, "<span class=\"badge\">EX 前递 %s</span>",
            (report->pipeline_features & 0x4u) != 0 ? "启用" : "关闭");
    fputs("<span class=\"badge\">互锁 启用</span>", output);
  }
  fputs("</div><div class=\"stall-grid\">", output);
  uint64_t stall_max = 1;
  for (size_t index = 0; index < PERFORMANCE_HTML_STALL_COUNT; index++) {
    if (report->stalls[index] > stall_max) stall_max = report->stalls[index];
  }
  static const char *stall_colors[] = {"var(--if)", "var(--id)", "var(--ex)", "var(--mem)", "var(--wb)"};
  for (size_t index = 0; index < PERFORMANCE_HTML_STALL_COUNT; index++) {
    const double percent = 100.0 * ratio(report->stalls[index], stall_max);
    fputs("<span>", output); write_html_string(output, stall_names[index]);
    fprintf(output, "</span><div class=\"track\"><div class=\"fill\" style=\"--c:%s;width:%.3f%%\"></div></div><strong>%'" PRIu64 "</strong>",
            stall_colors[index], percent, report->stalls[index]);
  }
  fputs("</div>", output);

  if (report->aggregate_row < report->timing_row_count) {
    const PerformanceHtmlTimingRow *aggregate = &report->timing_rows[report->aggregate_row];
    fputs("<div class=\"stage-stack\" aria-label=\"全部指令平均阶段驻留占比\">", output);
    static const char *stage_colors[] = {"var(--if)", "var(--id)", "var(--ex)", "var(--mem)", "var(--wb)"};
    for (size_t index = 0; index < PERFORMANCE_HTML_STAGE_COUNT; index++) {
      const double average = ratio(aggregate->stage_total[index], aggregate->count);
      fprintf(output, "<span style=\"--c:%s;flex-grow:%.6f\" title=\"%s 平均 %.2f 周期\">%s</span>",
              stage_colors[index], average, stage_names[index], average, stage_names[index]);
    }
    fputs("</div><div class=\"stage-legend\">", output);
    for (size_t index = 0; index < PERFORMANCE_HTML_STAGE_COUNT; index++) {
      fprintf(output, "<span><i style=\"--c:%s\"></i>%s %.2f</span>", stage_colors[index], stage_names[index],
              ratio(aggregate->stage_total[index], aggregate->count));
    }
    fputs("</div>", output);
  }
  fputs("</section></div></div><main><section><div class=\"table-tools\"><h2>指令类别时序</h2><div class=\"segments\" role=\"group\" aria-label=\"类别筛选\"><button class=\"active\" data-filter=\"all\">全部</button><button data-filter=\"summary\">汇总</button><button data-filter=\"load\">加载</button><button data-filter=\"store\">存储</button><button data-filter=\"multiply\">M 扩展</button></div></div>", output);
  fputs("<div class=\"table-wrap\"><table><thead><tr><th>类别</th><th>次数</th><th>IF avg</th><th>ID avg</th><th>EX avg</th><th>MEM avg</th><th>WB avg</th><th>平均延迟</th><th>最大延迟</th></tr></thead><tbody id=\"timingRows\">", output);
  for (size_t row_index = 0; row_index < report->timing_row_count; row_index++) {
    const PerformanceHtmlTimingRow *row = &report->timing_rows[row_index];
    if (row->count == 0) continue;
    fprintf(output, "<tr data-group=\"%s\"><td>", row_group(row->name));
    write_html_string(output, row->name);
    fprintf(output, "</td><td>%'" PRIu64 "</td>", row->count);
    double total_average = 0.0;
    for (size_t stage = 0; stage < PERFORMANCE_HTML_STAGE_COUNT; stage++) {
      const double average = ratio(row->stage_total[stage], row->count);
      total_average += average;
      fprintf(output, "<td>%.2f</td>", average);
    }
    fprintf(output, "<td>%.2f</td><td>%'" PRIu64 "</td></tr>", total_average, row->max_total);
  }
  fputs("</tbody></table></div></section><section><h2>最近一次分类样本</h2><div class=\"table-wrap\"><table><thead><tr><th>类别</th><th>次数</th><th>PC</th><th>机器码</th><th>IF</th><th>ID</th><th>EX</th><th>MEM</th><th>WB</th><th>延迟</th></tr></thead><tbody>", output);
  for (size_t row_index = 0; row_index < report->timing_row_count; row_index++) {
    const PerformanceHtmlTimingRow *row = &report->timing_rows[row_index];
    if (!row->detailed || row->count == 0) continue;
    fputs("<tr><td>", output); write_html_string(output, row->name);
    fprintf(output, "</td><td>%'" PRIu64 "</td><td>0x%016" PRIx64 "</td><td>0x%08" PRIx32 "</td>",
            row->count, row->last_pc, row->last_instruction);
    for (size_t stage = 0; stage < PERFORMANCE_HTML_STAGE_COUNT; stage++) {
      fprintf(output, "<td>%'" PRIu64 "</td>", row->last_stage[stage]);
    }
    fprintf(output, "<td>%'" PRIu64 "</td></tr>", sum_stages(row->last_stage));
  }
  fputs("</tbody></table></div></section><section><h2>最后提交</h2>", output);
  if (report->last_commit_valid) {
    fputs("<div class=\"last\"><div><span>类别</span><strong>", output); write_html_string(output, report->last_class);
    fprintf(output, "</strong></div><div><span>PC</span><strong>0x%016" PRIx64 "</strong></div><div><span>机器码</span><strong>0x%08" PRIx32 "</strong></div><div><span>提交间隔</span><strong>%'" PRIu64 " cycles</strong></div>",
            report->last_pc, report->last_instruction, report->last_interval);
    fputs("<div><span>提交序号</span><strong>", output);
    fprintf(output, "%'" PRIu64 " → %'" PRIu64, report->last_commits_before, report->last_commits_after);
    fputs("</strong></div>", output);
    for (size_t stage = 0; stage < PERFORMANCE_HTML_STAGE_COUNT; stage++) {
      fprintf(output, "<div><span>%s 驻留</span><strong>%'" PRIu64 " cycles</strong></div>", stage_names[stage], report->last_stage[stage]);
    }
    fputs("</div>", output);
  } else {
    fputs("<p class=\"muted\">尚无已提交指令。</p>", output);
  }
  if (report->instruction_html_available || report->pipeline_html_available) {
    fputs("<div class=\"actions\">", output);
    if (report->instruction_html_available) {
      fputs("<a href=\"instructions.html\" target=\"_blank\" rel=\"noopener\">查看逐指令明细</a>", output);
    }
    if (report->pipeline_html_available) {
      fputs("<a href=\"pipeline.html\" target=\"_blank\" rel=\"noopener\">查看流水线时间线</a>", output);
    }
    fputs("</div>", output);
  }
  fputs("</section></main><footer>阶段表统计的是每条已提交指令的阶段驻留与端到端延迟；全局 CPI/IPC 由硬件总周期与提交总数计算。</footer>"
        "<script>const buttons=[...document.querySelectorAll('[data-filter]')],rows=[...document.querySelectorAll('#timingRows tr')];buttons.forEach(button=>button.onclick=()=>{buttons.forEach(item=>item.classList.toggle('active',item===button));const filter=button.dataset.filter;rows.forEach(row=>row.hidden=filter!=='all'&&row.dataset.group!==filter)})</script></body></html>", output);
  return ferror(output) ? -1 : 0;
}

int performance_html_write(const char *output_path, const PerformanceHtmlReport *report) {
  if (output_path == NULL || output_path[0] == '\0' || report == NULL) {
    errno = EINVAL;
    return -1;
  }
  size_t temporary_size = strlen(output_path) + 48;
  char *temporary = malloc(temporary_size);
  if (temporary == NULL) return -1;
  snprintf(temporary, temporary_size, "%s.tmp.%ld", output_path, (long)getpid());
  FILE *output = fopen(temporary, "w");
  if (output == NULL) {
    free(temporary);
    return -1;
  }
  int status = write_document(output, report);
  if (fclose(output) != 0) status = -1;
  if (status == 0 && rename(temporary, output_path) != 0) status = -1;
  if (status != 0) unlink(temporary);
  free(temporary);
  return status;
}
