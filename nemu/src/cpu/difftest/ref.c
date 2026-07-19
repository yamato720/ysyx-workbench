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
#include <cpu/cpu.h>
#include <difftest-def.h>
#include <memory/paddr.h>

#ifdef ZCU102_RUNTIME_NEMU_REF
#include <utils.h>

void cpu_exec(uint64_t n);
void init_device();

__EXPORT void difftest_memcpy(paddr_t addr, void *buf, size_t n, bool direction) {
  if (direction == DIFFTEST_TO_REF) {
    memcpy(guest_to_host(addr), buf, n);
  } else if (direction == DIFFTEST_TO_DUT) {
    memcpy(buf, guest_to_host(addr), n);
  } else {
    panic("Unknown difftest direction %d", direction);
  }
}

__EXPORT void difftest_regcpy(void *dut, bool direction) {
#ifdef CONFIG_ISA_riscv
  if (direction == DIFFTEST_TO_REF) {
    riscv_difftest_unpack((const riscv_difftest_state_t *)dut);
  } else if (direction == DIFFTEST_TO_DUT) {
    riscv_difftest_pack((riscv_difftest_state_t *)dut);
  } else {
    panic("Unknown difftest direction %d", direction);
  }
#else
  if (direction == DIFFTEST_TO_REF) {
    memcpy(&cpu, dut, DIFFTEST_REG_SIZE);
    cpu.gpr[0] = 0;
  } else if (direction == DIFFTEST_TO_DUT) {
    memcpy(dut, &cpu, DIFFTEST_REG_SIZE);
  } else {
    panic("Unknown difftest direction %d", direction);
  }
#endif
}

__EXPORT void difftest_exec(uint64_t n) {
  cpu_exec(n);
}

__EXPORT void difftest_raise_intr(word_t NO) {
  cpu.pc = isa_raise_intr(NO, cpu.pc);
}

__EXPORT void difftest_init(int port) {
  (void)port;
  void init_mem();
  init_mem();
  IFDEF(CONFIG_DEVICE, init_device());
  /* Perform ISA dependent initialization. */
  init_isa();
  nemu_state.state = NEMU_STOP;
}
#else
#include "../../../src/monitor/sdb/sdb.h"
// #include "sim.h"

__EXPORT void difftest_memcpy(paddr_t addr, void *buf, size_t n, bool direction) {
  // if(direction == DIFFTEST_TO_REF) {
  //   paddr_write(addr, buf, n);
  // } else if (direction == DIFFTEST_TO_DUT) {
  //   paddr_read(addr, buf, n);
  // } else {
  //   panic("Unknown direction %d", direction);
  // }
  assert(0);
}
// void diff_step_once();

__EXPORT void difftest_regcpy(void *dut, bool direction) {
  assert(0);
}

__EXPORT void difftest_exec(uint64_t n) {
  // assert(0);
  // diff_step_once();
}

__EXPORT void difftest_raise_intr(word_t NO) {
  assert(0);
}

__EXPORT void difftest_init(int port) {
  void init_mem();
  init_mem();
  /* Perform ISA dependent initialization. */
  init_isa();
}
#endif
