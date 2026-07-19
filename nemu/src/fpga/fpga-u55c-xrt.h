#ifndef NEMU_FPGA_U55C_XRT_H
#define NEMU_FPGA_U55C_XRT_H

#include "fpga-mailbox.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct nemu_fpga_u55c_xrt {
  void *implementation;
  struct nemu_fpga_mailbox_io mailbox;
  size_t memory_size;
  char last_error[256];
  bool failed;
};

#ifdef __cplusplus
extern "C" {
#endif

int nemu_fpga_u55c_xrt_open(struct nemu_fpga_u55c_xrt *runtime,
                            unsigned device_index, const char *xclbin,
                            const char *kernel_name, unsigned memory_group,
                            size_t memory_size, uint32_t max_request_cycles);
int nemu_fpga_u55c_xrt_load(struct nemu_fpga_u55c_xrt *runtime, size_t offset,
                            const void *source, size_t size);
int nemu_fpga_u55c_xrt_read(struct nemu_fpga_u55c_xrt *runtime, size_t offset,
                            void *destination, size_t size);
bool nemu_fpga_u55c_xrt_failed(const struct nemu_fpga_u55c_xrt *runtime);
const char *nemu_fpga_u55c_xrt_error(const struct nemu_fpga_u55c_xrt *runtime);
void nemu_fpga_u55c_xrt_close(struct nemu_fpga_u55c_xrt *runtime);

#ifdef __cplusplus
}
#endif

#endif
