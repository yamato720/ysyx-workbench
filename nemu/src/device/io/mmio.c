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

#include <device/map.h>
#include <memory/paddr.h>
#include "../../../monitor/sdb/sdb.h"

#define NR_MAP 16

static IOMap maps[NR_MAP] = {};
static int nr_map = 0;

#if CONFIG_NPC_DIFFTEST_NEMU
typedef struct {
  bool write;
  paddr_t addr;
  int len;
  word_t data;
} NpcMmioReplay;

enum { NPC_MMIO_REPLAY_DEPTH = 16 };
// 硬件可在最老 store 提交前发射随后数条已确认的设备写，故必须按总线顺序缓存
// 多项事务；容量覆盖四项 LSU 完成表及设备端尚未被软件参考机消费的请求。
static NpcMmioReplay npc_mmio_replay[NPC_MMIO_REPLAY_DEPTH] = {};
static unsigned npc_mmio_replay_head = 0;
static unsigned npc_mmio_replay_tail = 0;
static unsigned npc_mmio_replay_count = 0;
static bool npc_hardware_mmio_access = false;

static void npc_record_hardware_mmio(bool write, paddr_t addr, int len, word_t data) {
  assert(npc_mmio_replay_count < NPC_MMIO_REPLAY_DEPTH);
  npc_mmio_replay[npc_mmio_replay_tail] = (NpcMmioReplay) {
    .write = write, .addr = addr, .len = len, .data = data
  };
  npc_mmio_replay_tail = (npc_mmio_replay_tail + 1) % NPC_MMIO_REPLAY_DEPTH;
  npc_mmio_replay_count++;
}

static bool npc_consume_hardware_mmio(bool write, paddr_t addr, int len, word_t data,
    word_t *replayed_data) {
  if (npc_hardware_mmio_access || npc_mmio_replay_count == 0) return false;
  NpcMmioReplay *replay = &npc_mmio_replay[npc_mmio_replay_head];
  assert(replay->write == write);
  assert(replay->addr == addr);
  assert(replay->len == len);
  if (write) assert(replay->data == data);
  else *replayed_data = replay->data;
  npc_mmio_replay_head = (npc_mmio_replay_head + 1) % NPC_MMIO_REPLAY_DEPTH;
  npc_mmio_replay_count--;
  return true;
}
#endif

static IOMap* fetch_mmio_map(paddr_t addr) {
  int mapid = find_mapid_by_addr(maps, nr_map, addr);
  return (mapid == -1 ? NULL : &maps[mapid]);
}

static void report_mmio_overlap(const char *name1, paddr_t l1, paddr_t r1,
    const char *name2, paddr_t l2, paddr_t r2) {
  panic("MMIO region %s@[" FMT_PADDR ", " FMT_PADDR "] is overlapped "
               "with %s@[" FMT_PADDR ", " FMT_PADDR "]", name1, l1, r1, name2, l2, r2);
}

/* device interface */
void add_mmio_map(const char *name, paddr_t addr, void *space, uint32_t len, io_callback_t callback) {
  assert(nr_map < NR_MAP);
  paddr_t left = addr, right = addr + len - 1;
  if (in_pmem(left) || in_pmem(right)) {
    report_mmio_overlap(name, left, right, "pmem", PMEM_LEFT, PMEM_RIGHT);
  }
  for (int i = 0; i < nr_map; i++) {
    if (left <= maps[i].high && right >= maps[i].low) {
      report_mmio_overlap(name, left, right, maps[i].name, maps[i].low, maps[i].high);
    }
  }

  maps[nr_map] = (IOMap){ .name = name, .low = addr, .high = addr + len - 1,
    .space = space, .callback = callback };
  Log("Add mmio map '%s' at [" FMT_PADDR ", " FMT_PADDR "]",
      maps[nr_map].name, maps[nr_map].low, maps[nr_map].high);

  nr_map ++;
}

/* bus interface */
word_t mmio_read(paddr_t addr, int len) {
#if CONFIG_NPC_DIFFTEST_NEMU
  word_t replayed_data = 0;
  if (npc_consume_hardware_mmio(false, addr, len, 0, &replayed_data)) return replayed_data;
#endif
  IOMap *map = fetch_mmio_map(addr);
  word_t data = map_read(addr, len, map);
  IFDEF(CONFIG_DTRACE, record_device_access(addr, len, data, false, map ? map->name : "unknown"));
  return data;
}

void mmio_write(paddr_t addr, int len, word_t data) {
#if CONFIG_NPC_DIFFTEST_NEMU
  word_t ignored_data = 0;
  if (npc_consume_hardware_mmio(true, addr, len, data, &ignored_data)) return;
#endif
  IOMap *map = fetch_mmio_map(addr);
  map_write(addr, len, data, map);
  IFDEF(CONFIG_DTRACE, record_device_access(addr, len, data, true, map ? map->name : "unknown"));
}

word_t npc_hardware_mmio_read(paddr_t addr, int len) {
#if CONFIG_NPC_DIFFTEST_NEMU
  npc_hardware_mmio_access = true;
  word_t data = mmio_read(addr, len);
  npc_hardware_mmio_access = false;
  npc_record_hardware_mmio(false, addr, len, data);
  return data;
#else
  return mmio_read(addr, len);
#endif
}

void npc_hardware_mmio_write(paddr_t addr, int len, word_t data) {
#if CONFIG_NPC_DIFFTEST_NEMU
  npc_hardware_mmio_access = true;
  mmio_write(addr, len, data);
  npc_hardware_mmio_access = false;
  npc_record_hardware_mmio(true, addr, len, data);
#else
  mmio_write(addr, len, data);
#endif
}
