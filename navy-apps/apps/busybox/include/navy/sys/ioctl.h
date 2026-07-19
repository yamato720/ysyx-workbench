#ifndef NAVY_BUSYBOX_SYS_IOCTL_H
#define NAVY_BUSYBOX_SYS_IOCTL_H

#define FIONREAD   0x541b
#define TIOCGWINSZ 0x5413
#define TIOCSWINSZ 0x5414

int ioctl(int fd, unsigned long request, ...);

#endif
