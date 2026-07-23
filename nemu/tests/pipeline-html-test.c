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
  assert(strstr(content, "href=\"performance.html\"") != NULL);
  free(content);
  assert(pipeline_html_write_instructions(empty) == 0);
  char instruction_path[512];
  snprintf(instruction_path, sizeof(instruction_path), "%s/instructions.html", directory);
  content = read_file(instruction_path);
  assert(strstr(content, "NEMU 逐指令明细") != NULL);
  assert(strstr(content, "href=\"performance.html\"") != NULL);
  assert(strstr(content, "搜索序号、PC、机器码或反汇编") != NULL);
  free(content);
  pipeline_html_destroy(empty);

  PipelineHtmlRecorder *escaped = pipeline_html_create(path, "case</script>", 3);
  assert(escaped != NULL);
  pipeline_html_record(escaped, 1, 0x80000000, 0x00100073,
                       "addi a0, a0, <&\\\"", 20, stage);
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
  assert(strstr(content, "\"dropped\":2") != NULL);
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
