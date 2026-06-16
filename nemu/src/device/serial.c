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

#include <utils.h>
#include <device/map.h>

/* http://en.wikibooks.org/wiki/Serial_Programming/8250_UART_Programming */
// NOTE: this is compatible to 16550

#define CH_OFFSET 0

static uint8_t *serial_base = NULL;


static void serial_putc(char ch) {
  MUXDEF(CONFIG_TARGET_AM, putch(ch), fputc(ch, stderr));
#ifndef CONFIG_TARGET_AM
  fflush(stderr);
#endif
}

static uint8_t serial_getc() {
#ifdef CONFIG_TARGET_AM
  return 0xff;
#else
  int ch = getchar();
  return ch == EOF ? 0xff : (uint8_t)ch;
#endif
}

static void serial_io_handler(uint32_t offset, int len, bool is_write) {
  // assert(len == 1);
  if(len != 1){
    printf("offset: %u, len: %d, is_write: %d\n", offset, len, is_write);
    nemu_state.state = NEMU_ABORT;
    return;
  }
  switch (offset) {
    /* We bind the serial port with the host stderr in NEMU. */
    case CH_OFFSET:
      if (is_write) serial_putc(serial_base[0]);
      else serial_base[0] = serial_getc();
      break;
    default: {
      printf("offset: %u, len: %d, is_write: %d\n", offset, len, is_write);
      nemu_state.state = NEMU_ABORT;
      return;
    };
  }
}

void init_serial() {
  serial_base = new_space(8);
#ifdef CONFIG_HAS_PORT_IO
  add_pio_map ("serial", CONFIG_SERIAL_PORT, serial_base, 8, serial_io_handler);
#else
  add_mmio_map("serial", CONFIG_SERIAL_MMIO, serial_base, 8, serial_io_handler);
#endif

}
