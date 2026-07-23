// DPI hooks required by ysyxSoC peripheral models in the NEMU-backed build.
// 主存由 SimAPBDpiRam 通过参数化的 pmem_read_word/pmem_write_word 提供。

extern "C" void mrom_read(int, int *data) {
    *data = 0;
}

extern "C" void flash_read(int, int *data) {
    *data = 0;
}
