#include <am.h>
#include <nemu.h>

static uint64_t boot_time = 0;

void __am_timer_init() {
  // 读取 NEMU 的 RTC 寄存器获取启动时间（微秒）
  boot_time = inl(RTC_ADDR + 4);
  boot_time = (boot_time << 32) | inl(RTC_ADDR);
}

void __am_timer_uptime(AM_TIMER_UPTIME_T *uptime) {
  // 读取当前时间并减去启动时间
  uint64_t current_time = inl(RTC_ADDR + 4);
  current_time = (current_time << 32) | inl(RTC_ADDR);
  uptime->us = current_time - boot_time;
}

void __am_timer_rtc(AM_TIMER_RTC_T *rtc) {
  rtc->second = 0;
  rtc->minute = 0;
  rtc->hour   = 0;
  rtc->day    = 0;
  rtc->month  = 0;
  rtc->year   = 1900;
}
