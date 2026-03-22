#include <am.h>
#include <nemu.h>
// #include <stdio.h>

#define KEYDOWN_MASK 0x8000

void __am_input_keybrd(AM_INPUT_KEYBRD_T *kbd) {
  uint32_t reg = inl(KBD_ADDR);
  kbd->keydown = (reg & KEYDOWN_MASK) != 0;
  kbd->keycode = reg & ~KEYDOWN_MASK;
}
