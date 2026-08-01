#ifndef __PIPELINE_HTML_H__
#define __PIPELINE_HTML_H__

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define PIPELINE_HTML_STAGE_COUNT 5
#define PIPELINE_HTML_DEFAULT_LIMIT 200000

typedef struct PipelineHtmlRecorder PipelineHtmlRecorder;

typedef struct {
  uint64_t start;
  uint64_t end;
} PipelineHtmlInterval;

typedef struct {
  bool valid;
  uint64_t queue_start_cycle;
  uint64_t service_start_cycle;
  uint64_t queue_cycles;
  uint64_t service_cycles;
} PipelineHtmlMemoryTiming;

void pipeline_html_compute_intervals(
    uint64_t commit_cycle,
    const uint64_t stage_cycles[PIPELINE_HTML_STAGE_COUNT],
    PipelineHtmlInterval intervals[PIPELINE_HTML_STAGE_COUNT]);

PipelineHtmlRecorder *pipeline_html_create(
    const char *output_path, const char *label, size_t limit);
void pipeline_html_record(
    PipelineHtmlRecorder *recorder,
    uint64_t sequence,
    uint64_t pc,
    uint32_t instruction,
    const char *disassembly,
    uint64_t commit_cycle,
    const uint64_t stage_cycles[PIPELINE_HTML_STAGE_COUNT]);
void pipeline_html_record_with_starts(
    PipelineHtmlRecorder *recorder,
    uint64_t sequence,
    uint64_t pc,
    uint32_t instruction,
    const char *disassembly,
    uint64_t commit_cycle,
    const uint64_t stage_cycles[PIPELINE_HTML_STAGE_COUNT],
    const uint64_t stage_starts[PIPELINE_HTML_STAGE_COUNT]);
void pipeline_html_record_with_starts_and_memory(
    PipelineHtmlRecorder *recorder,
    uint64_t sequence,
    uint64_t pc,
    uint32_t instruction,
    const char *disassembly,
    uint64_t commit_cycle,
    const uint64_t stage_cycles[PIPELINE_HTML_STAGE_COUNT],
    const uint64_t stage_starts[PIPELINE_HTML_STAGE_COUNT],
    const PipelineHtmlMemoryTiming *memory);
int pipeline_html_finish(
    PipelineHtmlRecorder *recorder,
    const uint64_t stalls[PIPELINE_HTML_STAGE_COUNT]);
int pipeline_html_write_instructions(PipelineHtmlRecorder *recorder);
void pipeline_html_destroy(PipelineHtmlRecorder *recorder);
size_t pipeline_html_captured(const PipelineHtmlRecorder *recorder);
uint64_t pipeline_html_dropped(const PipelineHtmlRecorder *recorder);

void npc_pipeline_html_init(void);
void npc_pipeline_html_record(
    uint64_t sequence,
    uint64_t pc,
    uint32_t instruction,
    const char *disassembly,
    uint64_t commit_cycle,
    const uint64_t stage_cycles[PIPELINE_HTML_STAGE_COUNT]);
void npc_pipeline_html_record_absolute(
    uint64_t sequence,
    uint64_t pc,
    uint32_t instruction,
    const char *disassembly,
    uint64_t commit_cycle,
    const uint64_t stage_cycles[PIPELINE_HTML_STAGE_COUNT],
    const uint64_t stage_starts[PIPELINE_HTML_STAGE_COUNT]);
void npc_pipeline_html_record_absolute_with_memory(
    uint64_t sequence,
    uint64_t pc,
    uint32_t instruction,
    const char *disassembly,
    uint64_t commit_cycle,
    const uint64_t stage_cycles[PIPELINE_HTML_STAGE_COUNT],
    const uint64_t stage_starts[PIPELINE_HTML_STAGE_COUNT],
    const PipelineHtmlMemoryTiming *memory);
void npc_pipeline_html_record_hardware(
    uint64_t sequence,
    uint64_t pc,
    uint32_t instruction,
    const char *disassembly,
    uint64_t commit_cycle,
    const uint64_t stage_cycles[PIPELINE_HTML_STAGE_COUNT],
    uint8_t saturation);
void npc_pipeline_html_set_dropped(uint64_t dropped);
void npc_pipeline_html_finalize(
    const uint64_t stalls[PIPELINE_HTML_STAGE_COUNT],
    bool write_pipeline_html);

#endif
