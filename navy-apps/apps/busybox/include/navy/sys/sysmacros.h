#ifndef NAVY_BUSYBOX_SYS_SYSMACROS_H
#define NAVY_BUSYBOX_SYS_SYSMACROS_H

#define major(dev) ((unsigned int)(((dev) >> 8) & 0xfff))
#define minor(dev) ((unsigned int)(((dev) & 0xff) | (((dev) >> 12) & 0xfff00)))
#define makedev(maj, min) ((((maj) & 0xfff) << 8) | ((min) & 0xff) | (((min) & 0xfff00) << 12))

#endif
