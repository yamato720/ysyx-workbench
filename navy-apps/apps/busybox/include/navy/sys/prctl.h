#ifndef NAVY_BUSYBOX_SYS_PRCTL_H
#define NAVY_BUSYBOX_SYS_PRCTL_H

#define PR_SET_NAME 15

int prctl(int option, ...);

#endif
