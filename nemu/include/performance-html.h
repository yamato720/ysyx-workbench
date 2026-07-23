#ifndef __PERFORMANCE_HTML_H__
#define __PERFORMANCE_HTML_H__

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define PERFORMANCE_HTML_STAGE_COUNT 5
#define PERFORMANCE_HTML_STALL_COUNT 5

typedef enum {
  PERFORMANCE_HTML_OUTCOME_GOOD,
  PERFORMANCE_HTML_OUTCOME_BAD,
  PERFORMANCE_HTML_OUTCOME_ABORT,
  PERFORMANCE_HTML_OUTCOME_QUIT,
} PerformanceHtmlOutcome;

typedef struct {
  const char *name;
  uint64_t count;
  uint64_t stage_total[PERFORMANCE_HTML_STAGE_COUNT];
  uint64_t max_total;
  bool detailed;
  uint64_t last_pc;
  uint32_t last_instruction;
  uint64_t last_stage[PERFORMANCE_HTML_STAGE_COUNT];
} PerformanceHtmlTimingRow;

typedef struct {
  const char *label;
  const char *mode;
  const char *outcome_text;
  PerformanceHtmlOutcome outcome;
  double clock_mhz;
  uint64_t cycles;
  uint64_t commits;
  uint64_t host_time_us;
  uint64_t guest_instructions;
  uint32_t pipeline_features;
  uint64_t stalls[PERFORMANCE_HTML_STALL_COUNT];
  bool last_commit_valid;
  const char *last_class;
  uint64_t last_pc;
  uint32_t last_instruction;
  uint64_t last_interval;
  uint64_t last_commits_before;
  uint64_t last_commits_after;
  uint64_t last_stage[PERFORMANCE_HTML_STAGE_COUNT];
  const PerformanceHtmlTimingRow *timing_rows;
  size_t timing_row_count;
  size_t aggregate_row;
  bool instruction_html_available;
  bool pipeline_html_available;
} PerformanceHtmlReport;

int performance_html_write(const char *output_path, const PerformanceHtmlReport *report);

#endif
