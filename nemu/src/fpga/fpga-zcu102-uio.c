#include "fpga-zcu102-uio.h"

#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static uint32_t uio_read32(void *opaque, uint32_t offset) {
  struct nemu_fpga_zcu102_uio *uio = opaque;
  if (uio == NULL || uio->control_mapping == MAP_FAILED ||
      offset > uio->control_mapping_size - sizeof(uint32_t)) {
    if (uio != NULL) uio->io_error = EFAULT;
    return UINT32_MAX;
  }
  atomic_thread_fence(memory_order_acquire);
  return *(volatile uint32_t *)((uint8_t *)uio->control_mapping + offset);
}

static void uio_write32(void *opaque, uint32_t offset, uint32_t value) {
  struct nemu_fpga_zcu102_uio *uio = opaque;
  if (uio == NULL || uio->control_mapping == MAP_FAILED ||
      offset > uio->control_mapping_size - sizeof(uint32_t)) {
    if (uio != NULL) uio->io_error = EFAULT;
    return;
  }
  *(volatile uint32_t *)((uint8_t *)uio->control_mapping + offset) = value;
  atomic_thread_fence(memory_order_seq_cst);
}

void nemu_fpga_zcu102_uio_close(struct nemu_fpga_zcu102_uio *uio) {
  if (uio == NULL) return;
  if (uio->control_mapping != NULL && uio->control_mapping != MAP_FAILED) {
    munmap(uio->control_mapping, uio->control_mapping_size);
  }
  if (uio->memory_mapping != NULL && uio->memory_mapping != MAP_FAILED) {
    munmap(uio->memory_mapping, uio->memory_mapping_size);
  }
  if (uio->control_fd >= 0) close(uio->control_fd);
  if (uio->memory_fd >= 0) close(uio->memory_fd);
  memset(uio, 0, sizeof(*uio));
  uio->control_fd = -1;
  uio->memory_fd = -1;
}

int nemu_fpga_zcu102_uio_open(struct nemu_fpga_zcu102_uio *uio,
                              const char *control_device,
                              size_t control_size,
                              const char *memory_device,
                              uint64_t memory_physical_address,
                              size_t memory_size,
                              uint32_t max_request_cycles) {
  if (uio == NULL || control_device == NULL || memory_device == NULL ||
      control_size < sizeof(uint32_t) || memory_size == 0) {
    errno = EINVAL;
    return -1;
  }
  memset(uio, 0, sizeof(*uio));
  uio->control_fd = -1;
  uio->memory_fd = -1;
  uio->control_mapping = MAP_FAILED;
  uio->memory_mapping = MAP_FAILED;

  const long page_size_long = sysconf(_SC_PAGESIZE);
  if (page_size_long <= 0) goto failure;
  const uint64_t page_size = (uint64_t)page_size_long;
  const uint64_t page_offset = memory_physical_address % page_size;
  const uint64_t aligned_physical = memory_physical_address - page_offset;
  if (memory_size > SIZE_MAX - page_offset) {
    errno = EOVERFLOW;
    goto failure;
  }

  uio->control_fd = open(control_device, O_RDWR | O_SYNC | O_CLOEXEC);
  if (uio->control_fd < 0) goto failure;
  uio->control_mapping_size = control_size;
  uio->control_mapping = mmap(NULL, control_size, PROT_READ | PROT_WRITE,
                              MAP_SHARED, uio->control_fd, 0);
  if (uio->control_mapping == MAP_FAILED) goto failure;

  uio->memory_fd = open(memory_device, O_RDWR | O_SYNC | O_CLOEXEC);
  if (uio->memory_fd < 0) goto failure;
  uio->memory_mapping_size = memory_size + (size_t)page_offset;
  uio->memory_mapping = mmap(NULL, uio->memory_mapping_size, PROT_READ | PROT_WRITE,
                             MAP_SHARED, uio->memory_fd, (off_t)aligned_physical);
  if (uio->memory_mapping == MAP_FAILED) goto failure;
  uio->guest_memory = (uint8_t *)uio->memory_mapping + page_offset;
  uio->guest_memory_size = memory_size;
  uio->mailbox = (struct nemu_fpga_mailbox_io) {
    .opaque = uio,
    .read32 = uio_read32,
    .write32 = uio_write32,
    .max_request_cycles = max_request_cycles,
  };
  return 0;

failure:
  {
    const int saved_errno = errno;
    nemu_fpga_zcu102_uio_close(uio);
    errno = saved_errno;
  }
  return -1;
}

int nemu_fpga_zcu102_uio_load(struct nemu_fpga_zcu102_uio *uio,
                              size_t guest_offset,
                              const void *image,
                              size_t image_size) {
  if (uio == NULL || image == NULL || uio->guest_memory == NULL ||
      guest_offset > uio->guest_memory_size ||
      image_size > uio->guest_memory_size - guest_offset) {
    errno = EINVAL;
    return -1;
  }
  if (nemu_fpga_runtime_hold_reset(&uio->mailbox) != 0) {
    errno = EIO;
    return -1;
  }
  memcpy(uio->guest_memory + guest_offset, image, image_size);
  atomic_thread_fence(memory_order_seq_cst);
  if (msync(uio->memory_mapping, uio->memory_mapping_size, MS_SYNC) != 0) return -1;
  return uio->io_error == 0 ? 0 : -1;
}

int nemu_fpga_zcu102_uio_read(struct nemu_fpga_zcu102_uio *uio,
                              size_t guest_offset, void *destination, size_t size) {
  if (uio == NULL || destination == NULL || uio->guest_memory == NULL ||
      guest_offset > uio->guest_memory_size ||
      size > uio->guest_memory_size - guest_offset) {
    errno = EINVAL;
    return -1;
  }
  atomic_thread_fence(memory_order_acquire);
  memcpy(destination, uio->guest_memory + guest_offset, size);
  return uio->io_error == 0 ? 0 : -1;
}
