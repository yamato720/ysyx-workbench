#include <pipeline-html.h>

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static char *read_file(const char *path) {
  FILE *input = fopen(path, "rb");
  assert(input != NULL);
  assert(fseek(input, 0, SEEK_END) == 0);
  long length = ftell(input);
  assert(length >= 0);
  rewind(input);
  char *content = malloc((size_t)length + 1);
  assert(content != NULL);
  assert(fread(content, (size_t)length, 1, input) == 1 || length == 0);
  content[length] = '\0';
  fclose(input);
  return content;
}

int main(void) {
  assert(setenv("NEMU_MEMORY_STATISTICS_MODE", "Split", 1) == 0);
  const uint64_t stage[PIPELINE_HTML_STAGE_COUNT] = {1, 3, 2, 1, 1};
  PipelineHtmlInterval intervals[PIPELINE_HTML_STAGE_COUNT];
  pipeline_html_compute_intervals(20, stage, intervals);
  assert(intervals[0].start == 13 && intervals[0].end == 13);
  assert(intervals[1].start == 14 && intervals[1].end == 16);
  assert(intervals[2].start == 17 && intervals[2].end == 18);
  assert(intervals[3].start == 19 && intervals[3].end == 19);
  assert(intervals[4].start == 20 && intervals[4].end == 20);

  char directory[] = "/tmp/nemu-pipeline-html-test.XXXXXX";
  assert(mkdtemp(directory) != NULL);
  char path[512];
  snprintf(path, sizeof(path), "%s/pipeline.html", directory);
  const uint64_t stalls[PIPELINE_HTML_STAGE_COUNT] = {2, 3, 5, 7, 11};

  PipelineHtmlRecorder *empty = pipeline_html_create(path, "empty", PIPELINE_HTML_DEFAULT_LIMIT);
  assert(empty != NULL);
  assert(pipeline_html_finish(empty, stalls) == 0);
  char *content = read_file(path);
  assert(strstr(content, "\"captured\":0") != NULL);
  assert(strstr(content, "没有匹配的已提交指令") != NULL);
  assert(strstr(content, "body{height:100vh") != NULL);
  assert(strstr(content, ".viewport{flex:1;min-height:0;overflow:auto;scrollbar-gutter:stable") != NULL);
  assert(strstr(content, ".viewport::-webkit-scrollbar{width:14px;height:14px}") != NULL);
  assert(strstr(content, "role=\"scrollbar\"") != NULL);
  assert(strstr(content, "function syncHorizontalScrollbar()") != NULL);
  assert(strstr(content, "--col-seq:64px;--col-pc:150px;--col-inst:112px;--col-asm:290px;--timeline-width:640px") != NULL);
  assert(strstr(content, ".row>.meta:nth-child(-n+4){position:sticky") != NULL);
  assert(strstr(content, ".row>.meta:nth-child(4){left:calc(var(--col-seq) + var(--col-pc) + var(--col-inst))") != NULL);
  assert(strstr(content, "viewport.style.setProperty('--timeline-width',timelineWidth+'px')") != NULL);
  assert(strstr(content, ">IF/ID<") != NULL);
  assert(strstr(content, ">REDIRECT<") != NULL);
  assert(strstr(content, ">COMMIT<") != NULL);
  assert(strstr(content, ">QUEUE<") != NULL);
  assert(strstr(content, ">ORDER<") != NULL);
  assert(strstr(content, "id=\"detailModal\"") != NULL);
  assert(strstr(content, "function timelineBlocks(record,next)") != NULL);
  assert(strstr(content, "function openDetail(record,block,target)") != NULL);
  assert(strstr(content, "trace.memory_statistics_mode!=='ServiceOnly'") != NULL);
  assert(strstr(content, "stage.handoff") != NULL);
  assert(strstr(content, "stage.redirect") != NULL);
  assert(strstr(content, "stage.commit") != NULL);
  assert(strstr(content, "stage.error") != NULL);
  assert(strstr(content, "nextBySequence") != NULL);
  assert(strstr(content, "TIMING OVERLAP") != NULL);
  assert(strstr(content, "function controlTransfer(record,next)") != NULL);
  assert(strstr(content, "Number.parseInt(next.pc,16)!==fallthrough") != NULL);
  assert(strstr(content, "按序等待较老指令") != NULL);
  assert(strstr(content, "b.addEventListener('click'") != NULL);
  assert(strstr(content, ".stage.handoff{background:var(--handoff)") != NULL);
  assert(strstr(content, ".stage.order") != NULL);
  assert(strstr(content, "href=\"performance.html\"") != NULL);
  free(content);
  assert(pipeline_html_write_instructions(empty) == 0);
  char instruction_path[512];
  snprintf(instruction_path, sizeof(instruction_path), "%s/instructions.html", directory);
  content = read_file(instruction_path);
  assert(strstr(content, "NEMU 逐指令明细") != NULL);
  assert(strstr(content, "href=\"performance.html\"") != NULL);
  assert(strstr(content, "<th>IF/ID</th>") != NULL);
  assert(strstr(content, "搜索序号、PC、机器码或反汇编") != NULL);
  free(content);
  pipeline_html_destroy(empty);

  assert(setenv("NEMU_MEMORY_STATISTICS_MODE", "ServiceOnly", 1) == 0);
  PipelineHtmlRecorder *service_only = pipeline_html_create(path, "service-only", PIPELINE_HTML_DEFAULT_LIMIT);
  assert(service_only != NULL);
  const uint64_t service_start[PIPELINE_HTML_STAGE_COUNT] = {3, 8, 12, 15, 19};
  const PipelineHtmlMemoryTiming service_memory = {
    .valid = true,
    .queue_start_cycle = 13,
    .service_start_cycle = 15,
    .queue_cycles = 2,
    .service_cycles = 4,
  };
  pipeline_html_record_with_starts_and_memory(
      service_only, 1, 0x80000000, 0x00100073, "lw", 20,
      stage, service_start, &service_memory);
  assert(pipeline_html_finish(service_only, stalls) == 0);
  content = read_file(path);
  assert(strstr(content, "\"memory_statistics_mode\":\"ServiceOnly\"") != NULL);
  free(content);
  pipeline_html_destroy(service_only);
  assert(setenv("NEMU_MEMORY_STATISTICS_MODE", "Split", 1) == 0);

  PipelineHtmlRecorder *escaped = pipeline_html_create(path, "case</script>", 3);
  assert(escaped != NULL);
  const uint64_t absolute_start[PIPELINE_HTML_STAGE_COUNT] = {3, 8, 12, 15, 19};
  const PipelineHtmlMemoryTiming memory = {
    .valid = true,
    .queue_start_cycle = 13,
    .service_start_cycle = 15,
    .queue_cycles = 2,
    .service_cycles = 4,
  };
  pipeline_html_record_with_starts_and_memory(
      escaped, 1, 0x80000000, 0x00100073, "addi a0, a0, <&\\\"", 20,
      stage, absolute_start, &memory);
  for (uint64_t index = 2; index <= 5; index++) {
    pipeline_html_record(escaped, index, 0x80000000 + index * 4,
                         0x00000013, "nop", 20 + index, stage);
  }
  assert(pipeline_html_captured(escaped) == 3);
  assert(pipeline_html_dropped(escaped) == 2);
  assert(pipeline_html_finish(escaped, stalls) == 0);
  content = read_file(path);
  assert(strstr(content, "case\\u003c/script\\u003e") != NULL);
  assert(strstr(content, "addi a0, a0, \\u003c\\u0026") != NULL);
  assert(strstr(content, "\"pc\":\"0x80000000\"") != NULL);
  assert(strstr(content, "\"absolute\":true") != NULL);
  assert(strstr(content, "\"s\":[3,8,12,15,19]") != NULL);
  assert(strstr(content, "\"memory_statistics_mode\":\"Split\"") != NULL);
  assert(strstr(content, "\"queueStart\":13") != NULL);
  assert(strstr(content, "\"serviceStart\":15") != NULL);
  assert(strstr(content, "\"queue\":2") != NULL);
  assert(strstr(content, "\"service\":4") != NULL);
  assert(strstr(content, "请求握手") != NULL);
  assert(strstr(content, "下游完成") != NULL);
  assert(strstr(content, "\"dropped\":2") != NULL);
  free(content);
  assert(pipeline_html_write_instructions(escaped) == 0);
  content = read_file(instruction_path);
  assert(strstr(content, "\"pc\":\"0x80000000\"") != NULL);
  assert(strstr(content, "\"absolute\":true") != NULL);
  assert(strstr(content, "\"s\":[3,8,12,15,19]") != NULL);
  free(content);
  pipeline_html_destroy(escaped);

  PipelineHtmlRecorder *limit = pipeline_html_create(path, "limit", PIPELINE_HTML_DEFAULT_LIMIT);
  assert(limit != NULL);
  const uint64_t one_cycle[PIPELINE_HTML_STAGE_COUNT] = {1, 1, 1, 1, 1};
  for (uint64_t index = 0; index < PIPELINE_HTML_DEFAULT_LIMIT + 17ULL; index++) {
    pipeline_html_record(limit, index + 1, 0x80000000 + index * 4,
                         0x00000013, "nop", index + 5, one_cycle);
  }
  assert(pipeline_html_captured(limit) == PIPELINE_HTML_DEFAULT_LIMIT);
  assert(pipeline_html_dropped(limit) == 17);
  pipeline_html_destroy(limit);

  unlink(path);
  unlink(instruction_path);
  assert(rmdir(directory) == 0);
  puts("pipeline HTML tests passed");
  return 0;
}
