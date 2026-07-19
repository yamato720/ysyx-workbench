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

#include <memory/host.h>
#include <memory/paddr.h>
#include <device/mmio.h>
#include <isa.h>
#include "../monitor/sdb/sdb.h"

#if   defined(CONFIG_PMEM_MALLOC)
static uint8_t *pmem = NULL;
#else // CONFIG_PMEM_GARRAY
static uint8_t pmem[CONFIG_MSIZE] PG_ALIGN = {};
#endif

#define MAX_MEM_ACCESS_STR_LEN 128
static char mem_access_str[MAX_MEM_ACCESS_STR_LEN][128];
static int mem_access_str_idx = 0;
static bool mem_traggered = false;


#define MAX_DEVICE_ACCESS_STR_LEN 128
static char device_access_str[MAX_DEVICE_ACCESS_STR_LEN][256];
static int device_access_str_idx = 0;
static bool device_traggered = false;
static bool device_access_wrapped = false;

void record_read_access(word_t addr, int len, word_t data) {
  word_t final_data;
  switch (len) {
    case 1: final_data = data & 0xFF; break;
    case 2: final_data = data & 0xFFFF; break;
    case 4: final_data = data & 0xFFFFFFFF; break;
    IFDEF(CONFIG_ISA64, case 8: final_data = data; break);
    default: final_data = data; break;
  }
  sprintf(mem_access_str[mem_access_str_idx], "[R] " FMT_WORD " len=%d data=" FMT_WORD, addr, len, final_data);
  mem_traggered = true;
}

void record_device_access(paddr_t addr, int len, word_t data, bool is_write, const char *dev_name) {
  char* type = is_write ? "Write" : "Read";
  word_t final_data;
  switch (len) {
    case 1: final_data = data & 0xFF; break;
    case 2: final_data = data & 0xFFFF; break;
    case 4: final_data = data & 0xFFFFFFFF; break;
    IFDEF(CONFIG_ISA64, case 8: final_data = data; break);
    default: final_data = data; break;
  }

  sprintf(device_access_str[device_access_str_idx], "[MMIO %s] dev=%-10s addr=" FMT_PADDR " len=%d data=" FMT_WORD, type, dev_name, addr, len, final_data);
  device_traggered = true;
}

void record_write_access(word_t addr, int len, word_t data) {
  word_t final_data;
  switch (len) {
    case 1: final_data = data & 0xFF; break;
    case 2: final_data = data & 0xFFFF; break;
    case 4: final_data = data & 0xFFFFFFFF; break;
    IFDEF(CONFIG_ISA64, case 8: final_data = data; break);
    default: final_data = data; break;
  }
  sprintf(mem_access_str[mem_access_str_idx], "[W] " FMT_WORD " len=%d data=" FMT_WORD, addr, len, final_data);
  mem_traggered = true;
}


void record_ins_info(){
  if(mem_traggered){
    mem_traggered = false;
    get_current_iringbuf(mem_access_str[mem_access_str_idx]);
    if(mem_access_str_idx < MAX_MEM_ACCESS_STR_LEN - 1){
      mem_access_str_idx++;
    } else {
      mem_access_str_idx = 0;
    }
  }
#ifdef CONFIG_DTRACE
  if(device_traggered){
    device_traggered = false;
    get_current_iringbuf(device_access_str[device_access_str_idx]);
    if(device_access_str_idx < MAX_DEVICE_ACCESS_STR_LEN - 1){
      device_access_str_idx++;
    } else {
      device_access_str_idx = 0;
      device_access_wrapped = true;
    }
  }
#endif
  return;
}

void show_mem_access() {
  printf("==== Memory Access Log ====\n");
  int count = mem_access_str_idx < MAX_MEM_ACCESS_STR_LEN ? mem_access_str_idx : MAX_MEM_ACCESS_STR_LEN;
  for (int i = 0; i < count; i++) {
    if (mem_access_str[i][0] != '\0') {
      printf("%s\n", mem_access_str[i]);
    }
  }
}

void show_device_access() {
  printf("==== Device Access Log ====\n");
  if (device_access_wrapped) {
    // Buffer has wrapped: print from oldest (current idx) to newest
    for (int i = device_access_str_idx; i < MAX_DEVICE_ACCESS_STR_LEN; i++) {
      if (device_access_str[i][0] != '\0') printf("%s\n", device_access_str[i]);
    }
    for (int i = 0; i < device_access_str_idx; i++) {
      if (device_access_str[i][0] != '\0') printf("%s\n", device_access_str[i]);
    }
  } else {
    for (int i = 0; i < device_access_str_idx; i++) {
      if (device_access_str[i][0] != '\0') printf("%s\n", device_access_str[i]);
    }
  }
}

uint8_t* guest_to_host(paddr_t paddr) { 
  return pmem + paddr - CONFIG_MBASE;
}
paddr_t host_to_guest(uint8_t *haddr) { 
  return haddr - pmem + CONFIG_MBASE; 
}

static word_t pmem_read(paddr_t addr, int len) {
  // printf("pmem_read addr: 0x%08x, len: %d\n", addr, len);
  word_t ret = host_read(guest_to_host(addr), len);
  // record_read_access(addr, len, ret);
  return ret;
}

static void pmem_write(paddr_t addr, int len, word_t data) {
  host_write(guest_to_host(addr), len, data);
  // record_write_access(addr, len, data);
}

static void out_of_bound(paddr_t addr) {
  panic("address = " FMT_PADDR " is out of bound of pmem [" FMT_PADDR ", " FMT_PADDR "] at pc = " FMT_WORD,
      addr, PMEM_LEFT, PMEM_RIGHT, cpu.pc);
}

void init_mem() {
#if   defined(CONFIG_PMEM_MALLOC)
  pmem = malloc(CONFIG_MSIZE);
  assert(pmem);
#endif
  IFDEF(CONFIG_MEM_RANDOM, memset(pmem, rand(), CONFIG_MSIZE));
  Log("physical memory area [" FMT_PADDR ", " FMT_PADDR "]", PMEM_LEFT, PMEM_RIGHT);
}

word_t paddr_read(paddr_t addr, int len) {
  // printf("paddr_read addr: 0x%08u, len: %d\n", addr, len);

  if (likely(in_pmem(addr))) return pmem_read(addr, len);
  IFDEF(CONFIG_DEVICE, return mmio_read(addr, len));
  out_of_bound(addr);
  return 0;
}

void paddr_write(paddr_t addr, int len, word_t data) {
  if (likely(in_pmem(addr))) { pmem_write(addr, len, data); return; }
  IFDEF(CONFIG_DEVICE, mmio_write(addr, len, data); return);
  out_of_bound(addr);
}
