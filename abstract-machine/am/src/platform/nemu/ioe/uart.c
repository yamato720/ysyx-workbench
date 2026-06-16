#include <am.h>
#include <nemu.h>

void __am_uart_tx(AM_UART_TX_T *uart) {
  outb(SERIAL_PORT, uart->data);
}

void __am_uart_rx(AM_UART_RX_T *uart) {
  uart->data = inb(SERIAL_PORT);
}
