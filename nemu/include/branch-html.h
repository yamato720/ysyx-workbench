#ifndef __BRANCH_HTML_H__
#define __BRANCH_HTML_H__

#include <stdbool.h>
#include <stdint.h>

#define BRANCH_HTML_DEFAULT_LIMIT 200000

void npc_branch_html_init(void);
void npc_branch_html_set_mode(bool telemetry_available, bool dynamic_enabled);
void npc_branch_html_record(
    uint64_t sequence,
    uint64_t pc,
    uint32_t instruction,
    const char *disassembly,
    uint64_t predicted_next_pc,
    uint64_t actual_next_pc,
    bool dynamic_enabled);
void npc_branch_html_finalize(void);

#endif
