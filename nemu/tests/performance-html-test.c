#include <performance-html.h>

#include <assert.h>
#include <errno.h>
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
  char directory[] = "/tmp/nemu-performance-html-test.XXXXXX";
  assert(mkdtemp(directory) != NULL);
  char path[512];
  snprintf(path, sizeof(path), "%s/performance.html", directory);

  const PerformanceHtmlTimingRow rows[] = {
    {
      .name = "load.<&\"test",
      .count = 4,
      .stage_total = {8, 12, 4, 24, 4},
      .max_total = 17,
      .detailed = true,
      .last_pc = 0x80000010,
      .last_instruction = 0x00052503,
      .last_stage = {2, 3, 1, 6, 1},
    },
    {
      .name = "all",
      .count = 10,
      .stage_total = {20, 25, 10, 30, 10},
      .max_total = 17,
    },
  };
  PerformanceHtmlReport report = {
    .label = "bubble<&\"sort",
    .mode = "NPC",
    .outcome_text = "通过",
    .outcome = PERFORMANCE_HTML_OUTCOME_GOOD,
    .clock_mhz = 300.0,
    .cycles = 42,
    .commits = 10,
    .host_time_us = 1250,
    .guest_instructions = 10,
    .pipeline_features = 7,
    .stalls = {3, 5, 7, 11, 13},
    .last_commit_valid = true,
    .last_class = "load.lw",
    .last_pc = 0x80000010,
    .last_instruction = 0x00052503,
    .last_interval = 6,
    .last_commits_before = 9,
    .last_commits_after = 10,
    .last_stage = {2, 3, 1, 6, 1},
    .timing_rows = rows,
    .timing_row_count = 2,
    .aggregate_row = 1,
    .instruction_html_available = true,
    .pipeline_html_available = true,
  };

  assert(performance_html_write(path, &report) == 0);
  char *content = read_file(path);
  assert(strstr(content, "NEMU 性能报告") != NULL);
  assert(strstr(content, "bubble&lt;&amp;&quot;sort") != NULL);
  assert(strstr(content, "load.&lt;&amp;&quot;test") != NULL);
  assert(strstr(content, ">4.2000<") != NULL);
  assert(strstr(content, ">0.2381<") != NULL);
  assert(strstr(content, "MEM backpressure") != NULL);
  assert(strstr(content, "data-filter=\"load\"") != NULL);
  assert(strstr(content, "0x0000000080000010") != NULL);
  assert(strstr(content, "href=\"instructions.html\" target=\"_blank\"") != NULL);
  assert(strstr(content, "href=\"pipeline.html\"") != NULL);
  assert(strstr(content, "rel=\"noopener\"") != NULL);
  free(content);

  report.cycles = 0;
  report.commits = 0;
  report.last_commit_valid = false;
  report.instruction_html_available = false;
  report.pipeline_html_available = false;
  assert(performance_html_write(path, &report) == 0);
  content = read_file(path);
  assert(strstr(content, "尚无提交") != NULL);
  assert(strstr(content, "尚无已提交指令") != NULL);
  assert(strstr(content, "href=\"pipeline.html\"") == NULL);
  assert(strstr(content, "href=\"instructions.html\"") == NULL);
  free(content);

  errno = 0;
  assert(performance_html_write(NULL, &report) == -1);
  assert(errno == EINVAL);
  unlink(path);
  assert(rmdir(directory) == 0);
  puts("performance HTML tests passed");
  return 0;
}
