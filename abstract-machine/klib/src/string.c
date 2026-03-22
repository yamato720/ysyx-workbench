#include <klib.h>
#include <klib-macros.h>
#include <stdint.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

size_t strlen(const char *start) {
  // NB: start is not checked for nullptr!
  const char* end = start;
  while (*end != '\0')
      ++end;
  return end - start;
}

char *strcpy(char *dst, const char *src) {
  char *ret = dst;
  while ((*dst++ = *src++) != '\0');
  return ret;
}

char *strncpy(char *dst, const char *src, size_t n) {
  char *ret = dst;
  while ((*dst++ = *src++) != '\0' && --n > 0);
  return ret;
}

char *strcat(char *dst, const char *src) {
  char *d = dst;
  while (*d != '\0') d++;
  while ((*d++ = *src++) != '\0');
  return dst;
}

int strcmp(const char *s1, const char *s2) {
  while (*s1 && (*s1 == *s2)) {
    s1++;
    s2++;
  }
  return *(unsigned char *)s1 - *(unsigned char *)s2;
}

int strncmp(const char *s1, const char *s2, size_t n) {
  while (*s1 && (*s1 == *s2) && n-- > 0) {
    s1++;
    s2++;
  }
  return *(unsigned char *)s1 - *(unsigned char *)s2;
}

void *memset(void *s, int c, size_t n) {
  unsigned char *p = s;
  while(n-- > 0) {
    *p++ = (unsigned char)c;
  }
  return s;
}

void *memmove(void *dst, const void *src, size_t n) {
  void *copy_src = (void *)malloc(n);
  memcpy(copy_src, src, n);
  memcpy(dst, copy_src, n);
  free(copy_src);
  return dst;
}

void *memcpy(void *out, const void *in, size_t n) {
  unsigned char *d = out;
  const unsigned char *s = in;
  while(n-- > 0) {
    *d++ = *s++;
  }
  return out;
}

int memcmp(const void *s1, const void *s2, size_t n) {
  while(n-- > 0) {
    unsigned char c1 = *((unsigned char *)s1++);
    unsigned char c2 = *((unsigned char *)s2++);
    if (c1 != c2) {
      return c1 - c2;
    }
  }
  return 0;
}

#endif
