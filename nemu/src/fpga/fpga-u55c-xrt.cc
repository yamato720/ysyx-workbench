#include "fpga-u55c-xrt.h"

#include <experimental/xrt_ip.h>
#include <xrt/xrt_bo.h>
#include <xrt/xrt_device.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>

struct u55c_xrt_implementation {
  std::unique_ptr<xrt::device> device;
  xrt::uuid xclbin_uuid;
  std::unique_ptr<xrt::ip> control;
  std::unique_ptr<xrt::bo> memory;
  uint8_t *mapped_memory = nullptr;
};

static void record_error(struct nemu_fpga_u55c_xrt *runtime, const char *message) {
  runtime->failed = true;
  std::snprintf(runtime->last_error, sizeof(runtime->last_error), "%s", message);
}

static uint32_t u55c_read32(void *opaque, uint32_t offset) {
  auto *runtime = static_cast<struct nemu_fpga_u55c_xrt *>(opaque);
  auto *implementation = static_cast<u55c_xrt_implementation *>(runtime->implementation);
  if (implementation == nullptr || implementation->control == nullptr) {
    record_error(runtime, "U55C XRT control interface is not open");
    return 0;
  }
  try {
    return implementation->control->read_register(offset);
  } catch (const std::exception &error) {
    record_error(runtime, error.what());
    return 0;
  }
}

static void u55c_write32(void *opaque, uint32_t offset, uint32_t value) {
  auto *runtime = static_cast<struct nemu_fpga_u55c_xrt *>(opaque);
  auto *implementation = static_cast<u55c_xrt_implementation *>(runtime->implementation);
  if (implementation == nullptr || implementation->control == nullptr) {
    record_error(runtime, "U55C XRT control interface is not open");
    return;
  }
  try {
    implementation->control->write_register(offset, value);
  } catch (const std::exception &error) {
    record_error(runtime, error.what());
  }
}

extern "C" int nemu_fpga_u55c_xrt_open(struct nemu_fpga_u55c_xrt *runtime,
                                         unsigned device_index, const char *xclbin,
                                         const char *kernel_name, unsigned memory_group,
                                         size_t memory_size,
                                         uint32_t max_request_cycles) {
  if (runtime == nullptr || xclbin == nullptr || *xclbin == '\0' ||
      kernel_name == nullptr || *kernel_name == '\0' || memory_size == 0) {
    errno = EINVAL;
    return -1;
  }

  std::memset(runtime, 0, sizeof(*runtime));
  auto implementation = std::make_unique<u55c_xrt_implementation>();
  try {
    implementation->device = std::make_unique<xrt::device>(device_index);
    implementation->xclbin_uuid = implementation->device->load_xclbin(xclbin);
    implementation->control = std::make_unique<xrt::ip>(
        *implementation->device, implementation->xclbin_uuid, kernel_name);
    implementation->memory = std::make_unique<xrt::bo>(
        *implementation->device, memory_size, xrt::bo::flags::normal, memory_group);
    implementation->mapped_memory = implementation->memory->map<uint8_t *>();
    if (implementation->mapped_memory == nullptr)
      throw std::runtime_error("XRT returned a null HBM mapping");

    runtime->implementation = implementation.release();
    runtime->memory_size = memory_size;
    runtime->mailbox.opaque = runtime;
    runtime->mailbox.read32 = u55c_read32;
    runtime->mailbox.write32 = u55c_write32;
    runtime->mailbox.max_request_cycles = max_request_cycles;

    auto *opened = static_cast<u55c_xrt_implementation *>(runtime->implementation);
    const uint64_t memory_address = opened->memory->address();
    u55c_write32(runtime, NEMU_FPGA_RT_MEMORY_HOST_BASE_LOW,
                 static_cast<uint32_t>(memory_address));
    u55c_write32(runtime, NEMU_FPGA_RT_MEMORY_HOST_BASE_HIGH,
                 static_cast<uint32_t>(memory_address >> 32));
    if (runtime->failed) throw std::runtime_error(runtime->last_error);
    return 0;
  } catch (const std::exception &error) {
    record_error(runtime, error.what());
    errno = EIO;
    if (runtime->implementation != nullptr) {
      delete static_cast<u55c_xrt_implementation *>(runtime->implementation);
      runtime->implementation = nullptr;
    }
    return -1;
  }
}

extern "C" int nemu_fpga_u55c_xrt_load(struct nemu_fpga_u55c_xrt *runtime,
                                         size_t offset, const void *source,
                                         size_t size) {
  if (runtime == nullptr || source == nullptr || offset > runtime->memory_size ||
      size > runtime->memory_size - offset) {
    errno = EINVAL;
    return -1;
  }
  auto *implementation = static_cast<u55c_xrt_implementation *>(runtime->implementation);
  if (implementation == nullptr || implementation->mapped_memory == nullptr) {
    errno = ENODEV;
    return -1;
  }
  try {
    std::memcpy(implementation->mapped_memory + offset, source, size);
    implementation->memory->sync(XCL_BO_SYNC_BO_TO_DEVICE, size, offset);
    return 0;
  } catch (const std::exception &error) {
    record_error(runtime, error.what());
    errno = EIO;
    return -1;
  }
}

extern "C" int nemu_fpga_u55c_xrt_read(struct nemu_fpga_u55c_xrt *runtime,
                                         size_t offset, void *destination,
                                         size_t size) {
  if (runtime == nullptr || destination == nullptr || offset > runtime->memory_size ||
      size > runtime->memory_size - offset) {
    errno = EINVAL;
    return -1;
  }
  auto *implementation = static_cast<u55c_xrt_implementation *>(runtime->implementation);
  if (implementation == nullptr || implementation->mapped_memory == nullptr) {
    errno = ENODEV;
    return -1;
  }
  try {
    implementation->memory->sync(XCL_BO_SYNC_BO_FROM_DEVICE, size, offset);
    std::memcpy(destination, implementation->mapped_memory + offset, size);
    return 0;
  } catch (const std::exception &error) {
    record_error(runtime, error.what());
    errno = EIO;
    return -1;
  }
}

extern "C" bool
nemu_fpga_u55c_xrt_failed(const struct nemu_fpga_u55c_xrt *runtime) {
  return runtime == nullptr || runtime->failed;
}

extern "C" const char *
nemu_fpga_u55c_xrt_error(const struct nemu_fpga_u55c_xrt *runtime) {
  return runtime == nullptr ? "U55C XRT runtime is null" : runtime->last_error;
}

extern "C" void nemu_fpga_u55c_xrt_close(struct nemu_fpga_u55c_xrt *runtime) {
  if (runtime == nullptr) return;
  delete static_cast<u55c_xrt_implementation *>(runtime->implementation);
  std::memset(runtime, 0, sizeof(*runtime));
}
