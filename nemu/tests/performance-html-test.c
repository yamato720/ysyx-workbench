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
  char cache_path[512];
  snprintf(cache_path, sizeof(cache_path), "%s/cache.html", directory);

  const PerformanceHtmlTimingRow rows[] = {
    {
      .name = "load.<&\"test",
      .count = 4,
      .stage_total = {8, 12, 4, 24, 4},
      .total_latency = 60,
      .max_total = 17,
      .detailed = true,
      .last_pc = 0x80000010,
      .last_instruction = 0x00052503,
      .last_stage = {2, 3, 1, 6, 1},
      .last_total_latency = 10,
    },
    {
      .name = "all",
      .count = 10,
      .stage_total = {20, 25, 10, 30, 10},
      .total_latency = 80,
      .max_total = 17,
    },
  };
  PerformanceHtmlReport report = {
    .label = "bubble<&\"sort",
    .mode = "NPC",
    .memory_statistics_mode = "Split",
    .outcome_text = "通过",
    .outcome = PERFORMANCE_HTML_OUTCOME_GOOD,
    .clock_mhz = 300.0,
    .cycles = 42,
    .commits = 10,
    .host_time_us = 1250,
    .guest_instructions = 10,
    .monitoring_available = true,
    .pipeline_features = 7,
    .stalls = {3, 5, 7, 11, 13},
    .cache_statistics_available = true,
    .cache = {{80, 20, 20, 0, 3}, {45, 25, 5, 2, 1}, {30, 10, 7, 1, 2}},
    .cache_configuration_available = true,
    .cache_configuration = {
      {
        .enabled = true, .capacity_bytes = 4096, .line_bytes = 16, .ways = 2, .sets = 128,
        .mapping = "set-associative", .replacement = "Tree-PLRU",
        .read_miss = "read-allocate", .write_policy = "write-through",
        .write_miss = "no-write-allocate", .storage = "auto",
      },
      {
        .enabled = true, .capacity_bytes = 4096, .line_bytes = 16, .ways = 2, .sets = 128,
        .mapping = "set-associative", .replacement = "Tree-PLRU",
        .read_miss = "read-allocate", .write_policy = "write-back",
        .write_miss = "write-allocate", .storage = "URAM",
      },
      {
        .enabled = true, .capacity_bytes = 262144, .line_bytes = 64, .ways = 8, .sets = 512,
        .mapping = "set-associative", .replacement = "Tree-PLRU",
        .read_miss = "read-allocate", .write_policy = "write-back",
        .write_miss = "write-allocate", .storage = "auto",
      },
    },
    .instruction_buffer_enabled = true,
    .instruction_buffer_entries = 4,
    .last_commit_valid = true,
    .last_class = "load.lw",
    .last_pc = 0x80000010,
    .last_instruction = 0x00052503,
    .last_interval = 6,
    .last_commits_before = 9,
    .last_commits_after = 10,
    .last_stage = {2, 3, 1, 6, 1},
    .last_total_latency = 10,
    .timing_rows = rows,
    .timing_row_count = 2,
    .aggregate_row = 1,
    .instruction_html_available = true,
    .cache_html_available = true,
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
  assert(strstr(content, "MEM 统计: Split") != NULL);
  assert(strstr(content, "QUEUE avg") != NULL);
  assert(strstr(content, ">15.00<") != NULL);
  assert(strstr(content, "端到端延迟") != NULL);
  assert(strstr(content, "缓存统计") == NULL);
  assert(strstr(content, "缓存配置") == NULL);
  assert(strstr(content, "href=\"cache.html\"") != NULL);
  assert(strstr(content, "data-filter=\"load\"") != NULL);
  assert(strstr(content, "0x0000000080000010") != NULL);
  assert(strstr(content, "href=\"instructions.html\"") != NULL);
  assert(strstr(content, "href=\"pipeline.html\"") != NULL);
  free(content);

  report.memory_statistics_mode = "ServiceOnly";
  assert(performance_html_write(path, &report) == 0);
  content = read_file(path);
  assert(strstr(content, "MEM 统计: ServiceOnly") != NULL);
  assert(strstr(content, "QUEUE avg") == NULL);
  assert(strstr(content, ">MEM avg<") != NULL);
  free(content);
  report.memory_statistics_mode = "Split";

  assert(performance_html_write_cache(cache_path, &report) == 0);
  content = read_file(cache_path);
  assert(strstr(content, "NEMU 缓存报告") != NULL);
  assert(strstr(content, "缓存配置") == NULL);
  assert(strstr(content, "缓存统计") == NULL);
  assert(strstr(content, "L1 I$") != NULL);
  assert(strstr(content, "L1 D$") != NULL);
  assert(strstr(content, "128 x 2") != NULL);
  assert(strstr(content, "Tree-PLRU") != NULL);
  assert(strstr(content, "write-back") != NULL);
  assert(strstr(content, "顺序取指缓冲：启用，4 entries") != NULL);
  assert(strstr(content, "缓存命中率") != NULL);
  assert(strstr(content, ">80.00%<") != NULL);
  assert(strstr(content, ">90.00%<") != NULL);
  assert(strstr(content, "旁路/不分配") != NULL);
  assert(strstr(content, "本层命中率") != NULL);
  assert(strstr(content, ">75.00%<") != NULL);
  assert(strstr(content, "L2$") != NULL);
  assert(strstr(content, "512 x 8") != NULL);
  assert(strstr(content, "href=\"performance.html\"") != NULL);
  free(content);

  report.trace_dropped = 7;
  report.trace_saturated_records = 2;
  report.latest_samples_are_trace_prefix = true;
  assert(performance_html_write(path, &report) == 0);
  content = read_file(path);
  assert(strstr(content, "分类 trace 前缀中的最近样本") != NULL);
  assert(strstr(content, "分类样本表仅代表已保存前缀") != NULL);
  assert(strstr(content, "65535 周期") != NULL);
  free(content);
  report.trace_dropped = 0;
  report.trace_saturated_records = 0;
  report.latest_samples_are_trace_prefix = false;

  report.cycles = 0;
  report.commits = 0;
  report.last_commit_valid = false;
  report.instruction_html_available = false;
  report.cache_html_available = false;
  report.pipeline_html_available = false;
  assert(performance_html_write(path, &report) == 0);
  content = read_file(path);
  assert(strstr(content, "尚无提交") != NULL);
  assert(strstr(content, "尚无已提交指令") != NULL);
  assert(strstr(content, "href=\"cache.html\"") == NULL);
  assert(strstr(content, "href=\"pipeline.html\"") == NULL);
  assert(strstr(content, "href=\"instructions.html\"") == NULL);
  free(content);

  report.monitoring_available = false;
  assert(performance_html_write(path, &report) == 0);
  content = read_file(path);
  assert(strstr(content, "未启用 U55C v13 performance-monitor") != NULL);
  assert(strstr(content, "data-filter=\"load\"") == NULL);
  free(content);

  errno = 0;
  assert(performance_html_write(NULL, &report) == -1);
  assert(errno == EINVAL);
  errno = 0;
  assert(performance_html_write_cache(NULL, &report) == -1);
  assert(errno == EINVAL);
  unlink(path);
  unlink(cache_path);
  assert(rmdir(directory) == 0);
  puts("performance HTML tests passed");
  return 0;
}
