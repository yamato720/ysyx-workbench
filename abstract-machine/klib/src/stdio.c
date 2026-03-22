#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <stdarg.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

// 格式化标志
typedef struct {
  int width;       // 字段宽度
  int precision;   // 精度（小数点后位数）
  char pad_char;   // 填充字符 ('0' 或 ' ')
  bool left_align; // 左对齐
  bool sign_plus;  // 总是显示符号
  bool sign_space; // 正数前加空格
  bool alt_form;   // 替代形式（0x前缀等）
} FormatFlags;

// 内联辅助函数：输出单个字符到缓冲区
static inline void buf_putc(char **buf, char c) {
  if (buf && *buf) {
    **buf = c;
    (*buf)++;
  } else {
    putch(c);
  }
}

// 内联辅助函数：输出字符串到缓冲区
static inline void buf_puts(char **buf, const char *s) {
  while (*s) {
    buf_putc(buf, *s++);
  }
}

// 内联辅助函数：输出 n 个相同字符
static inline void buf_put_repeat(char **buf, char c, int count) {
  while (count-- > 0) {
    buf_putc(buf, c);
  }
}

// 内联函数：将整数转换为字符串（支持不同进制）
static inline int itoa_buf(long long num, char *buf, int base, bool uppercase, bool is_signed) {
  const char *digits = uppercase ? "0123456789ABCDEF" : "0123456789abcdef";
  char temp[32];
  int i = 0;
  bool negative = false;
  
  if (is_signed && num < 0) {
    negative = true;
    num = -num;
  }
  
  // 特殊情况：0
  if (num == 0) {
    temp[i++] = '0';
  } else {
    // 转换数字
    unsigned long long unum = (unsigned long long)num;
    while (unum > 0) {
      temp[i++] = digits[unum % base];
      unum /= base;
    }
  }
  
  // 反转到输出缓冲区
  int len = i;
  if (negative) {
    buf[0] = '-';
    for (int j = 0; j < len; j++) {
      buf[j + 1] = temp[len - 1 - j];
    }
    return len + 1;
  } else {
    for (int j = 0; j < len; j++) {
      buf[j] = temp[len - 1 - j];
    }
    return len;
  }
}

// 格式化整数输出（支持宽度、填充、对齐）
static inline void format_int(char **out, long long num, FormatFlags *flags, int base, bool uppercase, bool is_signed) {
  char num_buf[32];
  int num_len = itoa_buf(num, num_buf, base, uppercase, is_signed);
  
  // 计算需要的填充
  int pad_len = (flags->width > num_len) ? (flags->width - num_len) : 0;
  
  // 左对齐：先输出数字，再填充
  if (flags->left_align) {
    for (int i = 0; i < num_len; i++) {
      buf_putc(out, num_buf[i]);
    }
    buf_put_repeat(out, ' ', pad_len);
  } else {
    // 右对齐：先填充，再输出数字
    buf_put_repeat(out, flags->pad_char, pad_len);
    for (int i = 0; i < num_len; i++) {
      buf_putc(out, num_buf[i]);
    }
  }
}

// 格式化字符串输出（支持宽度、精度）
static inline void format_string(char **out, const char *str, FormatFlags *flags) {
  if (!str) str = "(null)";
  
  int len = strlen(str);
  if (flags->precision >= 0 && flags->precision < len) {
    len = flags->precision;
  }
  
  int pad_len = (flags->width > len) ? (flags->width - len) : 0;
  
  if (flags->left_align) {
    for (int i = 0; i < len; i++) {
      buf_putc(out, str[i]);
    }
    buf_put_repeat(out, ' ', pad_len);
  } else {
    buf_put_repeat(out, ' ', pad_len);
    for (int i = 0; i < len; i++) {
      buf_putc(out, str[i]);
    }
  }
}

// 解析格式说明符
static inline const char* parse_format(const char *fmt, FormatFlags *flags, va_list *args) {
  // 初始化标志
  flags->width = 0;
  flags->precision = -1;
  flags->pad_char = ' ';
  flags->left_align = false;
  flags->sign_plus = false;
  flags->sign_space = false;
  flags->alt_form = false;
  
  // 解析标志
  bool parsing_flags = true;
  while (parsing_flags) {
    switch (*fmt) {
      case '-': flags->left_align = true; fmt++; break;
      case '+': flags->sign_plus = true; fmt++; break;
      case ' ': flags->sign_space = true; fmt++; break;
      case '#': flags->alt_form = true; fmt++; break;
      case '0': 
        if (!flags->left_align) {
          flags->pad_char = '0';
        }
        fmt++; 
        break;
      default: parsing_flags = false; break;
    }
  }
  
  // 解析宽度
  if (*fmt >= '1' && *fmt <= '9') {
    while (*fmt >= '0' && *fmt <= '9') {
      flags->width = flags->width * 10 + (*fmt - '0');
      fmt++;
    }
  } else if (*fmt == '*') {
    flags->width = va_arg(*args, int);
    fmt++;
  }
  
  // 解析精度
  if (*fmt == '.') {
    fmt++;
    flags->precision = 0;
    if (*fmt >= '0' && *fmt <= '9') {
      while (*fmt >= '0' && *fmt <= '9') {
        flags->precision = flags->precision * 10 + (*fmt - '0');
        fmt++;
      }
    } else if (*fmt == '*') {
      flags->precision = va_arg(*args, int);
      fmt++;
    }
  }
  
  // 跳过长度修饰符（简化处理）
  while (*fmt == 'h' || *fmt == 'l' || *fmt == 'L' || *fmt == 'z' || *fmt == 't') {
    fmt++;
  }
  
  return fmt;
}

// 核心格式化函数
static int do_printf(char **out, const char *fmt, va_list args) {
  int count = 0;
  
  while (*fmt) {
    if (*fmt != '%') {
      buf_putc(out, *fmt++);
      count++;
      continue;
    }
    
    fmt++; // 跳过 '%'
    
    // 处理 "%%"
    if (*fmt == '%') {
      buf_putc(out, '%');
      fmt++;
      count++;
      continue;
    }
    
    // 解析格式说明符
    FormatFlags flags;
    fmt = parse_format(fmt, &flags, &args);
    
    // 处理转换说明符
    switch (*fmt) {
      case 'c': {
        char c = (char)va_arg(args, int);
        int pad = (flags.width > 1) ? (flags.width - 1) : 0;
        if (flags.left_align) {
          buf_putc(out, c);
          buf_put_repeat(out, ' ', pad);
        } else {
          buf_put_repeat(out, ' ', pad);
          buf_putc(out, c);
        }
        count += 1 + pad;
        break;
      }
      
      case 's': {
        const char *str = va_arg(args, const char*);
        format_string(out, str, &flags);
        count += flags.width > 0 ? flags.width : strlen(str ? str : "(null)");
        break;
      }
      
      case 'd':
      case 'i': {
        int num = va_arg(args, int);
        format_int(out, num, &flags, 10, false, true);
        count += flags.width;
        break;
      }
      
      case 'u': {
        unsigned int num = va_arg(args, unsigned int);
        format_int(out, num, &flags, 10, false, false);
        count += flags.width;
        break;
      }
      
      case 'x': {
        unsigned int num = va_arg(args, unsigned int);
        format_int(out, num, &flags, 16, false, false);
        count += flags.width;
        break;
      }
      
      case 'X': {
        unsigned int num = va_arg(args, unsigned int);
        format_int(out, num, &flags, 16, true, false);
        count += flags.width;
        break;
      }
      
      case 'o': {
        unsigned int num = va_arg(args, unsigned int);
        format_int(out, num, &flags, 8, false, false);
        count += flags.width;
        break;
      }
      
      case 'p': {
        void *ptr = va_arg(args, void*);
        buf_puts(out, "0x");
        format_int(out, (unsigned long)ptr, &flags, 16, false, false);
        count += 2 + flags.width;
        break;
      }
      
      default:
        buf_putc(out, '%');
        buf_putc(out, *fmt);
        count += 2;
        break;
    }
    
    fmt++;
  }
  
  return count;
}

int printf(const char *fmt, ...) {
  va_list args;
  va_start(args, fmt);
  int ret = do_printf(NULL, fmt, args);
  va_end(args);
  return ret;
}

int sprintf(char *out, const char *fmt, ...) {
  va_list args;
  va_start(args, fmt);
  char *buf_ptr = out;
  int ret = do_printf(&buf_ptr, fmt, args);
  *buf_ptr = '\0'; // null-terminate
  va_end(args);
  return ret;
}

int vsprintf(char *out, const char *fmt, va_list ap) {
  char *buf_ptr = out;
  int ret = do_printf(&buf_ptr, fmt, ap);
  *buf_ptr = '\0';
  return ret;
}

int snprintf(char *out, size_t n, const char *fmt, ...) {
  // 简化实现：暂不考虑 n 的限制
  va_list args;
  va_start(args, fmt);
  char *buf_ptr = out;
  int ret = do_printf(&buf_ptr, fmt, args);
  if (n > 0) {
    out[n - 1] = '\0';
  }
  va_end(args);
  return ret;
}

int vsnprintf(char *out, size_t n, const char *fmt, va_list ap) {
  char *buf_ptr = out;
  int ret = do_printf(&buf_ptr, fmt, ap);
  if (n > 0) {
    out[n - 1] = '\0';
  }
  return ret;
}

#endif
