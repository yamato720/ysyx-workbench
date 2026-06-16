/***************************************************************************************
* Copyright (c) 2014-2024 Zihao Yu, Nanjing University
*
* NEMU is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#include <isa.h>
#include "local-include/reg.h"

const char *regs[] = {
  "$0", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
  "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
  "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
  "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"
};
// ---------------------------------------------------------------------------
// CSR / other-register table
// Each entry: { name, pointer-to-current-value, stored-value-for-diff }
// Add a new row here when a new CSR is implemented in cpu.
// ---------------------------------------------------------------------------
typedef struct {
  const char *name;
  word_t     *cur;     // points into cpu struct (NULL = not implemented)
  word_t      stored;  // snapshot taken at the last stored_gpr() call
} CsrEntry;

// Sentinel value used when NPC does not expose a getter yet
#define CSR_NOT_IMPL ((word_t)-1ULL)

static CsrEntry csr_table[] = {
  // name        cur-pointer (NEMU only)   stored
#ifndef NPC
  { "pc",      (word_t*)&cpu.pc,       0 },
  { "mstatus", &cpu.mstatus,           0 },
  { "mcause",  &cpu.mcause,            0 },
  { "mepc",    (word_t*)&cpu.mepc,     0 },
  { "mtvec",   (word_t*)&cpu.mtvec,    0 },
#else
  { "pc",      NULL, 0 },
  { "mstatus", NULL, 0 },
  { "mcause",  NULL, 0 },
  { "mepc",    NULL, 0 },
  { "mtvec",   NULL, 0 },
#endif
};
#define NR_CSRS ((int)(sizeof(csr_table) / sizeof(csr_table[0])))

// Keep legacy arrays alive so existing callers (check_reg / isa_reg_str2val)
// that index into other_regs_val / other_regs_val_stored still work.
// They are now just aliases into csr_table and are updated in sync.
word_t reg_val[32] = {0};

// ---------------------------------------------------------------------------

static word_t csr_read_cur(int i) {
#ifdef NPC
  extern uint64_t npc_get_pc();
  if (strcmp(csr_table[i].name, "pc") == 0) return (word_t)npc_get_pc();
  return CSR_NOT_IMPL;
#else
  return csr_table[i].cur ? *csr_table[i].cur : CSR_NOT_IMPL;
#endif
}

void stored_gpr() {
#ifndef NPC
  for (int i = 0; i < 32; i++) reg_val[i] = gpr(i);
#else
  for (int i = 0; i < 32; i++) reg_val[i] = npc_get_reg(i);
#endif
  for (int i = 0; i < NR_CSRS; i++) csr_table[i].stored = csr_read_cur(i);
}

void update_other_regs() {
  // nothing to do: csr_table[i].cur already points live into cpu
  // (read on demand via csr_read_cur)
}

void check_reg(int idx, bool *success) {
  if (idx < 32) {
#ifndef NPC
    *success = (gpr(idx) != reg_val[idx]);
#else
    extern uint64_t npc_get_reg(int idx);
    *success = (npc_get_reg(idx) != reg_val[idx]);
#endif
    return;
  }
  int ci = idx - 32;
  if (ci < NR_CSRS) {
    word_t cur = csr_read_cur(ci);
    *success = (cur != CSR_NOT_IMPL) && (cur != csr_table[ci].stored);
    return;
  }
  *success = false;
}


// Format one register slot into buf (up to buflen bytes).
// Layout: "  %8s: " FMT_WORD "  %-22s" or "  %8s: not implemented"
// Total slot width = 2+8+2+18+2+22+1(when register update) = 55 chars (fixed for alignment).
#define SLOT_WIDTH 55
static void fmt_reg_slot(char *buf, int buflen,
                         const char *name, word_t stored,
                         bool not_impl, bool changed, word_t cur) {
  if (not_impl) {
    snprintf(buf, buflen, "  %8s: not implemented", name);
  } else {
    char status[32];
    if (changed)
      snprintf(status, sizeof(status), "new: " FMT_WORD, cur);
    else
      snprintf(status, sizeof(status), "no update");
    snprintf(buf, buflen, "  %8s: " FMT_WORD "  %s", name, stored, status);
  }
}

void isa_reg_display() {
  char slot[128];

  // ── common registers ─────────────────────────────────────────────────
  printf("common:\n");
  for (int i = 0; i < 32; i++) {
#ifndef NPC
    word_t cur = gpr(i);
#else
    extern uint64_t npc_get_reg(int idx);
    word_t cur = (word_t)npc_get_reg(i);
#endif
    fmt_reg_slot(slot, sizeof(slot), regs[i], reg_val[i],
                 false, cur != reg_val[i], cur);
    if (i % 2 == 0)
      printf("%-*s", SLOT_WIDTH, slot);
    else
      printf("%s\n", slot);
  }
  // 32 is even, no trailing newline needed

  // ── CSR registers ────────────────────────────────────────────────────
  printf("csr:\n");
  for (int i = 0; i < NR_CSRS; i++) {
    word_t cur = csr_read_cur(i);
    bool not_impl = (cur == CSR_NOT_IMPL);
    fmt_reg_slot(slot, sizeof(slot), csr_table[i].name, csr_table[i].stored,
                 not_impl, !not_impl && cur != csr_table[i].stored, cur);
    if (i % 2 == 0)
      printf("%-*s", SLOT_WIDTH, slot);
    else
      printf("%s\n", slot);
  }
  if (NR_CSRS % 2 != 0) printf("\n");
}

word_t isa_reg_str2val(const char *s, bool *success, int *idx) {
  for (int i = 0; i < 32; i++) {
    if (strcmp(s, regs[i]) == 0) {
      *success = true;
      *idx = i;
#ifndef NPC
      return gpr(i);
#else
      extern uint64_t npc_get_reg(int idx);
      return npc_get_reg(i);
#endif
    }
  }
  for (int i = 0; i < NR_CSRS; i++) {
    if (strcmp(s, csr_table[i].name) == 0) {
      *success = true;
      *idx = i + 32;
      word_t v = csr_read_cur(i);
      return (v == CSR_NOT_IMPL) ? 0 : v;
    }
  }
  *success = false;
  return 0;
}

word_t isa_reg_idx2val(int idx) {
  if (idx < 32) {
#ifndef NPC
    return gpr(idx);
#else
    extern uint64_t npc_get_reg(int idx);
    return npc_get_reg(idx);
#endif
  }
  int ci = idx - 32;
  if (ci < NR_CSRS) {
    word_t v = csr_read_cur(ci);
    return (v == CSR_NOT_IMPL) ? 0 : v;
  }
  return 0;
}

const char *isa_reg_idx2str(int idx) {
  if (idx < 32) return regs[idx];
  int ci = idx - 32;
  if (ci < NR_CSRS) return csr_table[ci].name;
  return "UNK";
}
