#ifndef NEMU_FPGA_ZCU102_UIO_H
#define NEMU_FPGA_ZCU102_UIO_H

#include <stddef.h>
#include <stdint.h>

#include "fpga-mailbox.h"

struct nemu_fpga_zcu102_uio {
  int control_fd;
  int memory_fd;
  void *control_mapping;
  size_t control_mapping_size;
  void *memory_mapping;
  size_t memory_mapping_size;
  uint8_t *guest_memory;
  size_t guest_memory_size;
  int io_error;
  struct nemu_fpga_mailbox_io mailbox;
};

int nemu_fpga_zcu102_uio_open(struct nemu_fpga_zcu102_uio *uio,
                              const char *control_device,
                              size_t control_size,
                              const char *memory_device,
                              uint64_t memory_physical_address,
                              size_t memory_size,
                              uint32_t max_request_cycles);
void nemu_fpga_zcu102_uio_close(struct nemu_fpga_zcu102_uio *uio);
int nemu_fpga_zcu102_uio_load(struct nemu_fpga_zcu102_uio *uio,
                              size_t guest_offset,
                              const void *image,
                              size_t image_size);
int nemu_fpga_zcu102_uio_read(struct nemu_fpga_zcu102_uio *uio,
                              size_t guest_offset, void *destination, size_t size);

#endif
