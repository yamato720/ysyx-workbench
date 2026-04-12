#ifndef __FS_H__
#define __FS_H__

#include <common.h>

#ifndef SEEK_SET
enum {SEEK_SET, SEEK_CUR, SEEK_END};
#endif

int         fs_open      (const char *pathname, int flags, int mode);
ssize_t     fs_read      (int fd, void *buf, size_t len);
ssize_t     fs_write     (int fd, const void *buf, size_t len);
int         fs_close     (int fd);
intptr_t    fs_lseek     (int fd, intptr_t offset, int whence);
const char *fs_first_file(void);  // name of first user file in ramdisk

#endif
