#include <cstdio>
extern "C" {
    int load_image(const char *filename);
    char pmem_read_a(int addr);
}

int main() {
    load_image("../am-kernels/tests/cpu-tests/build/add-riscv64-nemu.bin");
    printf("Address 0x80000000-0x80000003:\n");
    for (int i = 0; i < 4; i++) {
        unsigned char byte = (unsigned char)pmem_read_a(0x80000000 + i);
        printf("  [0x%08x] = 0x%02x\n", 0x80000000 + i, byte);
    }
    unsigned inst = 0;
    for (int i = 0; i < 4; i++) {
        unsigned char byte = (unsigned char)pmem_read_a(0x80000000 + i);
        inst |= (byte << (i * 8));
    }
    printf("Assembled instruction: 0x%08x\n", inst);
    return 0;
}
