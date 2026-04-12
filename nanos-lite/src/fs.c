#include <fs.h>

typedef size_t (*ReadFn) (void *buf, size_t offset, size_t len);
typedef size_t (*WriteFn) (const void *buf, size_t offset, size_t len);

typedef struct {
  char *name;
  size_t size;
  size_t disk_offset;
  ReadFn read;
  WriteFn write;
  size_t open_offset;   // current seek position within the file
} Finfo;

enum {FD_STDIN, FD_STDOUT, FD_STDERR, FD_FB};

size_t invalid_read(void *buf, size_t offset, size_t len) {
  panic("should not reach here");
  return 0;
}

size_t invalid_write(const void *buf, size_t offset, size_t len) {
  panic("should not reach here");
  return 0;
}

extern size_t serial_write(const void *buf, size_t offset, size_t len);
extern size_t ramdisk_read(void *buf, size_t offset, size_t len);
extern size_t ramdisk_write(const void *buf, size_t offset, size_t len);

/* This is the information about all files in disk.
 * Files from files.h use positional init {name, size, disk_offset};
 * their read/write pointers default to NULL → handled via ramdisk. */
static Finfo file_table[] __attribute__((used)) = {
  [FD_STDIN]  = {"stdin",  0, 0, invalid_read, invalid_write},
  [FD_STDOUT] = {"stdout", 0, 0, invalid_read, serial_write},
  [FD_STDERR] = {"stderr", 0, 0, invalid_read, serial_write},
#include "files.h"
};

#define NR_FILES (int)(sizeof(file_table) / sizeof(file_table[0]))

void init_fs() {
  // TODO: initialize the size of /dev/fb
}

/* Return the name of the first user file (index after stderr) in the ramdisk. */
const char *fs_first_file(void) {
  return file_table[FD_STDERR + 1].name;
}

/* Open a file by pathname; returns fd (index into file_table), or -1. */
int fs_open(const char *pathname, int flags, int mode) {
  for (int i = 0; i < NR_FILES; i++) {
    if (strcmp(file_table[i].name, pathname) == 0) {
      file_table[i].open_offset = 0;
      return i;
    }
  }
  panic("fs_open: '%s' not found", pathname);
  return -1;
}

/* Read len bytes from fd into buf; returns bytes actually read. */
ssize_t fs_read(int fd, void *buf, size_t len) {
  assert(fd >= 0 && fd < NR_FILES);
  Finfo *f = &file_table[fd];

  if (f->read != NULL) {
    // device file: delegate to its read handler
    ssize_t ret = f->read(buf, f->open_offset, len);
    f->open_offset += ret;
    return ret;
  }

  // regular ramdisk file: clamp to remaining bytes
  if (f->open_offset >= f->size) return 0;
  size_t available = f->size - f->open_offset;
  if (len > available) len = available;
  ramdisk_read(buf, f->disk_offset + f->open_offset, len);
  f->open_offset += len;
  return len;
}

/* Write len bytes from buf to fd; returns bytes actually written. */
ssize_t fs_write(int fd, const void *buf, size_t len) {
  assert(fd >= 0 && fd < NR_FILES);
  Finfo *f = &file_table[fd];

  if (f->write != NULL) {
    // device file (e.g. stdout/stderr/fb): delegate to its write handler
    ssize_t ret = f->write(buf, f->open_offset, len);
    f->open_offset += ret;
    return ret;
  }

  // regular ramdisk file
  ramdisk_write(buf, f->disk_offset + f->open_offset, len);
  f->open_offset += len;
  return len;
}

/* Close fd; resets seek position. Always succeeds. */
int fs_close(int fd) {
  assert(fd >= 0 && fd < NR_FILES);
  file_table[fd].open_offset = 0;
  return 0;
}

/* Reposition the open_offset of fd; returns new offset or -1 on error. */
intptr_t fs_lseek(int fd, intptr_t offset, int whence) {
  assert(fd >= 0 && fd < NR_FILES);
  Finfo *f = &file_table[fd];

  switch (whence) {
    case SEEK_SET: f->open_offset = offset; break;
    case SEEK_CUR: f->open_offset = (intptr_t)f->open_offset + offset; break;
    case SEEK_END: f->open_offset = (intptr_t)f->size   + offset; break;
    default: panic("fs_lseek: invalid whence %d", whence);
  }
  return f->open_offset;
}
