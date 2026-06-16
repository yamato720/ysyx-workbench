#ifndef LIB_NAVY_H
#define LIB_NAVY_H 1

#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

// asm-generic/termios.h
#define TIOCGWINSZ    0x5413
struct winsize {
	unsigned short ws_row;
	unsigned short ws_col;
	unsigned short ws_xpixel;
	unsigned short ws_ypixel;
};

// errno.h
#define __errno_location __errno

// signal.h
#define SA_RESTART  0x10000000

// sys/wait.h
#ifndef WCOREDUMP
#define WCOREDUMP(status) 0
#endif

ssize_t getline(char **lineptr, size_t *n, FILE *stream);
ssize_t getdelim(char **lineptr, size_t *n, int delim, FILE *stream);
int clearenv(void);

#endif
