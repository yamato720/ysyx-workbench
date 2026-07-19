// DPI hooks required by ysyxSoC peripheral models in the NEMU-backed build.
// Main memory is supplied by SimAPBDpiRam through pmem_read_64/write_64.

extern "C" void mrom_read(int, int *data) {
    *data = 0;
}

extern "C" void flash_read(int, int *data) {
    *data = 0;
}
