#include <am.h>
#include <nemu.h>

#define SYNC_ADDR (VGACTL_ADDR + 4)



void __am_gpu_config(AM_GPU_CONFIG_T *cfg) {
  uint32_t reg = inl(VGACTL_ADDR);
  *cfg = (AM_GPU_CONFIG_T) {
    .present = true, .has_accel = false,
    .width  = reg >> 16,
    .height = reg & 0xffff,
    .vmemsz = 0
  };
}

void __am_gpu_fbdraw(AM_GPU_FBDRAW_T *ctl) {
  int x = ctl->x, y = ctl->y, w = ctl->w, h = ctl->h;
  if (ctl->pixels) {
    uint32_t screen_w = inl(VGACTL_ADDR) >> 16;
    uint32_t *fb  = (uint32_t *)(uintptr_t)FB_ADDR;
    uint32_t *src = (uint32_t *)ctl->pixels;
    for (int row = 0; row < h; row++) {
      for (int col = 0; col < w; col++) {
        fb[(y + row) * screen_w + (x + col)] = src[row * w + col];
      }
    }
  }
  if (ctl->sync) {
    outl(SYNC_ADDR, 1);
  }
}

void __am_gpu_status(AM_GPU_STATUS_T *status) {
  status->ready = true;
}

void __am_gpu_init() {
  uint32_t reg = inl(VGACTL_ADDR);
  int w = reg >> 16;
  int h = reg & 0xffff;
  uint32_t *fb = (uint32_t *)(uintptr_t)FB_ADDR;
  for (int i = 0; i < w * h; i++) fb[i] = 0;
  outl(SYNC_ADDR, 1);
}
