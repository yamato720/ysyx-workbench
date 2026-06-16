#define _GNU_SOURCE

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef ZCU102_ROOT
#define ZCU102_ROOT "."
#endif

#define MAX_TESTS 256
#define MAX_LINE 256
#define CONFIG_REL "build/busy-box-nano/config"

typedef enum {
  SW_CPU_TEST,
  SW_AM_TEST,
  SW_ALU_TEST,
  SW_NAVY_TEST,
  SW_NAVY_APP,
  SW_NANOS_DEFAULT,
  SW_NANOS_NAVY_TEST,
  SW_NANOS_NAVY_APP,
} SoftwareKind;

typedef struct {
  char name[96];
  int is_dir;
  int item_index;
} BrowserEntry;

typedef struct {
  char name[96];
  char path[160];
  char folder[128];
  char make_name[96];
  char mainargs[160];
  SoftwareKind kind;
} SoftwareItem;

typedef struct {
  const char *name;
  const char *am_arch;
  const char *ps_bin;
  int use_npc;
} CpuArch;

typedef struct {
  SoftwareItem software[MAX_TESTS];
  int count;
  int selected;
  int arch;
  int last_arch;
  int batch;  // auto-run guest by passing NEMU batch mode
  int rebuild;
  char mainargs[160];
} ShellState;

static const char *root = ZCU102_ROOT;
static const CpuArch arches[] = {
  {"riscv64-nemu", "riscv64-nemu", "zcu102-ps-nemu-sdb-rv64", 0},
  {"riscv64-npc", "riscv64-nemu", NULL, 1},
  {"riscv32-nemu", "riscv32-nemu", "zcu102-ps-nemu-sdb-rv32", 0},
  {"riscv32-npc", "riscv32-nemu", NULL, 1},
};

static void save_config(const ShellState *st);
static const CpuArch *selected_arch(const ShellState *st);
static int find_test(const ShellState *st, const char *name);
static int is_navy_kind(SoftwareKind kind);
static void select_interactive_software(ShellState *st, int idx);
static int write_nanos_stamp(const ShellState *st, const SoftwareItem *test);
static int nanos_counterpart_index(const ShellState *st, int idx);

static void config_path(char *path, size_t size) {
  snprintf(path, size, "%s/%s", root, CONFIG_REL);
}

static void nanos_stamp_path(const ShellState *st, char *path, size_t size) {
  snprintf(path, size, "%s/build/busy-box-nano/nanos-image-%s.stamp",
           root, selected_arch(st)->name);
}

static int run_cmd(const char *cmd) {
  printf("+ %s\n", cmd);
  fflush(stdout);
  int status = system(cmd);
  if (status == -1) {
    perror("system");
    return 127;
  }
  if (WIFEXITED(status)) {
    return WEXITSTATUS(status);
  }
  return 128;
}

static int mkdir_p(const char *path) {
  char buf[PATH_MAX];
  snprintf(buf, sizeof(buf), "%s", path);
  for (char *p = buf + 1; *p; p++) {
    if (*p == '/') {
      *p = '\0';
      if (mkdir(buf, 0775) != 0 && errno != EEXIST) {
        perror(buf);
        return -1;
      }
      *p = '/';
    }
  }
  if (mkdir(buf, 0775) != 0 && errno != EEXIST) {
    perror(buf);
    return -1;
  }
  return 0;
}

static int is_regular_file(const char *path) {
  struct stat st;
  return stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

static int ends_with(const char *s, const char *suffix) {
  size_t a = strlen(s);
  size_t b = strlen(suffix);
  return a >= b && strcmp(s + a - b, suffix) == 0;
}

static int compare_tests(const void *a, const void *b) {
  const SoftwareItem *ta = (const SoftwareItem *)a;
  const SoftwareItem *tb = (const SoftwareItem *)b;
  return strcmp(ta->path, tb->path);
}

static int compare_browser_entries(const void *a, const void *b) {
  const BrowserEntry *ea = (const BrowserEntry *)a;
  const BrowserEntry *eb = (const BrowserEntry *)b;
  if (ea->is_dir != eb->is_dir) {
    return eb->is_dir - ea->is_dir;
  }
  return strcmp(ea->name, eb->name);
}

static int add_software(ShellState *st, SoftwareKind kind, const char *folder,
                        const char *name, const char *make_name, const char *mainargs) {
  if (st->count == MAX_TESTS) {
    return -1;
  }
  SoftwareItem *item = &st->software[st->count++];
  snprintf(item->name, sizeof(item->name), "%s", name);
  snprintf(item->folder, sizeof(item->folder), "%s", folder);
  snprintf(item->path, sizeof(item->path), "%s/%s", folder, name);
  snprintf(item->make_name, sizeof(item->make_name), "%s", make_name);
  snprintf(item->mainargs, sizeof(item->mainargs), "%s", mainargs ? mainargs : "");
  item->kind = kind;
  return 0;
}

static int read_makefile_name(const char *mk_path, char *name, size_t size) {
  FILE *fp = fopen(mk_path, "r");
  if (fp == NULL) {
    return -1;
  }

  char line[MAX_LINE];
  while (fgets(line, sizeof(line), fp) != NULL) {
    char *p = line;
    while (isspace((unsigned char)*p)) {
      p++;
    }
    if (strncmp(p, "NAME", 4) != 0) {
      continue;
    }
    p += 4;
    while (isspace((unsigned char)*p)) {
      p++;
    }
    if (*p != '=' && !(p[0] == ':' && p[1] == '=') && !(p[0] == '?' && p[1] == '=')) {
      continue;
    }
    p += (*p == '=') ? 1 : 2;
    while (isspace((unsigned char)*p)) {
      p++;
    }
    p[strcspn(p, " \t\r\n")] = '\0';
    if (p[0] != '\0') {
      snprintf(name, size, "%s", p);
      fclose(fp);
      return 0;
    }
  }

  fclose(fp);
  return -1;
}

static int discover_navy_dir(ShellState *st, const char *rel_dir,
                             const char *folder, SoftwareKind navy_kind,
                             const char *nanos_folder, SoftwareKind nanos_kind) {
  char abs_dir[PATH_MAX];
  snprintf(abs_dir, sizeof(abs_dir), "%s/%s", root, rel_dir);
  DIR *dir = opendir(abs_dir);
  if (dir == NULL) {
    return 0;
  }

  struct dirent *ent;
  while ((ent = readdir(dir)) != NULL) {
    if (ent->d_name[0] == '.') {
      continue;
    }

    char mk[PATH_MAX + NAME_MAX + 16];
    int len = snprintf(mk, sizeof(mk), "%s/%s/Makefile", abs_dir, ent->d_name);
    if (len < 0 || (size_t)len >= sizeof(mk)) {
      continue;
    }
    struct stat stbuf;
    if (stat(mk, &stbuf) != 0 || !S_ISREG(stbuf.st_mode)) {
      continue;
    }

    char prog[96];
    if (read_makefile_name(mk, prog, sizeof(prog)) != 0) {
      continue;
    }

    if (add_software(st, navy_kind, folder, ent->d_name, prog, "") != 0 ||
        add_software(st, nanos_kind, nanos_folder, ent->d_name, prog, "") != 0) {
      closedir(dir);
      return -1;
    }
  }

  closedir(dir);
  return 0;
}

static int discover_software(ShellState *st) {
  char tests_dir[PATH_MAX];
  snprintf(tests_dir, sizeof(tests_dir), "%s/nemu-src/am-kernels/tests/cpu-tests/tests", root);

  DIR *dir = opendir(tests_dir);
  if (dir == NULL) {
    perror(tests_dir);
    return -1;
  }

  st->count = 0;
  struct dirent *ent;
  while ((ent = readdir(dir)) != NULL) {
    if (!ends_with(ent->d_name, ".c")) {
      continue;
    }
    char name[96];
    snprintf(name, sizeof(name), "%s", ent->d_name);
    name[strlen(name) - 2] = '\0';
    if (add_software(st, SW_CPU_TEST, "cpu-tests", name, name, "") != 0) {
      break;
    }
  }
  closedir(dir);

  add_software(st, SW_AM_TEST, "am-tests", "hello", "am-hello", "h");
  add_software(st, SW_AM_TEST, "am-tests", "interrupt-yield", "am-interrupt-yield", "i");
  add_software(st, SW_AM_TEST, "am-tests", "devscan", "am-devscan", "d");
  add_software(st, SW_AM_TEST, "am-tests", "multiprocessor", "am-multiprocessor", "m");
  add_software(st, SW_AM_TEST, "am-tests", "rtc", "am-rtc", "t");
  add_software(st, SW_AM_TEST, "am-tests", "keyboard", "am-keyboard", "k");
  add_software(st, SW_AM_TEST, "am-tests", "video", "am-video", "v");
  add_software(st, SW_AM_TEST, "am-tests", "audio", "am-audio", "a");
  add_software(st, SW_AM_TEST, "am-tests", "vm", "am-vm", "p");
  add_software(st, SW_AM_TEST, "am-tests", "help", "am-help", "H");
  add_software(st, SW_ALU_TEST, "alu-tests", "alutest", "alutest", "");
  add_software(st, SW_NANOS_DEFAULT, "nanos-lite", "default-ramdisk", "nanos-lite", "");

  if (discover_navy_dir(st, "nemu-src/navy-apps/tests", "navy-apps/tests",
                        SW_NAVY_TEST, "nanos-lite/tests", SW_NANOS_NAVY_TEST) != 0 ||
      discover_navy_dir(st, "nemu-src/navy-apps/apps", "navy-apps/apps",
                        SW_NAVY_APP, "nanos-lite/apps", SW_NANOS_NAVY_APP) != 0) {
    return -1;
  }

  qsort(st->software, st->count, sizeof(st->software[0]), compare_tests);
  return st->count == 0 ? -1 : 0;
}

static int starts_with_path_prefix(const char *path, const char *prefix) {
  if (prefix[0] == '\0') {
    return 1;
  }
  size_t n = strlen(prefix);
  return strncmp(path, prefix, n) == 0 && path[n] == '/';
}

static const char *path_remainder(const char *path, const char *prefix) {
  if (prefix[0] == '\0') {
    return path;
  }
  if (!starts_with_path_prefix(path, prefix)) {
    return NULL;
  }
  return path + strlen(prefix) + 1;
}

static int browser_entry_index(const BrowserEntry *entries, int count,
                               const char *name, int is_dir) {
  for (int i = 0; i < count; i++) {
    if (entries[i].is_dir == is_dir && strcmp(entries[i].name, name) == 0) {
      return i;
    }
  }
  return -1;
}

static int collect_browser_entries(const ShellState *st, const char *prefix,
                                   BrowserEntry *entries, int max_entries) {
  int count = 0;
  for (int i = 0; i < st->count; i++) {
    const char *rest = path_remainder(st->software[i].path, prefix);
    if (rest == NULL || rest[0] == '\0') {
      continue;
    }

    char name[96];
    const char *slash = strchr(rest, '/');
    int is_dir = slash != NULL;
    size_t len = is_dir ? (size_t)(slash - rest) : strlen(rest);
    if (len >= sizeof(name)) {
      len = sizeof(name) - 1;
    }
    memcpy(name, rest, len);
    name[len] = '\0';

    int existing = browser_entry_index(entries, count, name, is_dir);
    if (existing >= 0) {
      if (!is_dir) {
        entries[existing].item_index = i;
      }
      continue;
    }
    if (count == max_entries) {
      break;
    }
    snprintf(entries[count].name, sizeof(entries[count].name), "%s", name);
    entries[count].is_dir = is_dir;
    entries[count].item_index = is_dir ? -1 : i;
    count++;
  }

  qsort(entries, count, sizeof(entries[0]), compare_browser_entries);
  return count;
}

static void print_software_level(const ShellState *st, const char *prefix) {
  BrowserEntry entries[MAX_TESTS];
  int count = collect_browser_entries(st, prefix, entries, MAX_TESTS);

  printf("\n%s:\n", prefix[0] ? prefix : "software");
  for (int i = 0; i < count; i++) {
    if (entries[i].is_dir) {
      printf("  %2d. %s/\n", i + 1, entries[i].name);
    } else {
      const SoftwareItem *item = &st->software[entries[i].item_index];
      printf("  %2d. %-22s %s\n", i + 1, entries[i].name,
             item->mainargs[0] ? "(fixed mainargs)" : "");
    }
  }
}

static void print_tests(const ShellState *st) {
  print_software_level(st, "");
}

static int arch_count(void) {
  return (int)(sizeof(arches) / sizeof(arches[0]));
}

static const CpuArch *selected_arch(const ShellState *st) {
  if (st->arch < 0 || st->arch >= arch_count()) {
    return &arches[0];
  }
  return &arches[st->arch];
}

static int find_arch(const char *name) {
  for (int i = 0; i < arch_count(); i++) {
    if (strcmp(arches[i].name, name) == 0) {
      return i;
    }
  }
  return -1;
}

static void print_arches(const ShellState *st) {
  puts("\narches:");
  for (int i = 0; i < arch_count(); i++) {
    printf("  %2d. %-12s %s\n", i + 1, arches[i].name,
           i == st->arch ? "(selected)" : "");
  }
}

static int choose_arch(ShellState *st) {
  print_arches(st);
  printf("\nselect arch [1-%d], q to return: ", arch_count());
  fflush(stdout);

  char line[MAX_LINE];
  if (fgets(line, sizeof(line), stdin) == NULL) {
    return -1;
  }
  line[strcspn(line, "\r\n")] = '\0';
  if (strcmp(line, "q") == 0 || strcmp(line, "quit") == 0 ||
      strcmp(line, "b") == 0 || strcmp(line, "back") == 0) {
    return 0;
  }
  int choice = atoi(line);
  if (choice < 1 || choice > arch_count()) {
    puts("invalid selection");
    return -1;
  }
  st->arch = choice - 1;
  save_config(st);
  return 0;
}

static int choose_test(ShellState *st) {
  char line[MAX_LINE];
  char prefix[160] = "";
  while (1) {
    BrowserEntry entries[MAX_TESTS];
    int count = collect_browser_entries(st, prefix, entries, MAX_TESTS);
    print_software_level(st, prefix);
    printf("\nselect software/folder, q to %s: ", prefix[0] ? "return" : "main menu");
    fflush(stdout);
    if (fgets(line, sizeof(line), stdin) == NULL) {
      return -1;
    }
    line[strcspn(line, "\r\n")] = '\0';
    if (line[0] == '\0') {
      continue;
    }
    if (strcmp(line, "q") == 0 || strcmp(line, "quit") == 0 ||
        strcmp(line, "b") == 0 || strcmp(line, "back") == 0) {
      char *slash = strrchr(prefix, '/');
      if (slash == NULL) {
        prefix[0] = '\0';
        return 0;
      }
      *slash = '\0';
      continue;
    }

    if (strchr(line, '/') != NULL) {
      int idx = find_test(st, line);
      if (idx >= 0) {
        select_interactive_software(st, idx);
        save_config(st);
        return 0;
      }
      puts("invalid software path");
      continue;
    }

    int entry_idx = -1;
    int choice = atoi(line);
    if (choice >= 1 && choice <= count) {
      entry_idx = choice - 1;
    } else {
      for (int i = 0; i < count; i++) {
        if (strcmp(entries[i].name, line) == 0) {
          entry_idx = i;
          break;
        }
      }
    }

    if (entry_idx < 0) {
      puts("invalid selection");
      continue;
    }

    if (entries[entry_idx].is_dir) {
      char next[160];
      snprintf(next, sizeof(next), "%s%s%s",
               prefix[0] ? prefix : "", prefix[0] ? "/" : "", entries[entry_idx].name);
      snprintf(prefix, sizeof(prefix), "%s", next);
    } else {
      select_interactive_software(st, entries[entry_idx].item_index);
      save_config(st);
      return 0;
    }
  }
}

static const SoftwareItem *selected_test(const ShellState *st) {
  if (st->selected < 0 || st->selected >= st->count) {
    return NULL;
  }
  return &st->software[st->selected];
}

static int find_test(const ShellState *st, const char *name) {
  const char *lookup = name;
  if (strcmp(name, "am-tests/serial/hello") == 0) {
    lookup = "am-tests/hello";
  } else if (strcmp(name, "am-tests/interrupt/yield") == 0) {
    lookup = "am-tests/interrupt-yield";
  }
  for (int i = 0; i < st->count; i++) {
    if (strcmp(st->software[i].path, lookup) == 0 || strcmp(st->software[i].name, lookup) == 0) {
      return i;
    }
  }
  return -1;
}

static int nanos_counterpart_index(const ShellState *st, int idx) {
  if (idx < 0 || idx >= st->count) {
    return idx;
  }
  const SoftwareItem *item = &st->software[idx];
  if (!is_navy_kind(item->kind)) {
    return idx;
  }

  char path[160];
  snprintf(path, sizeof(path), "nanos-lite/%s/%s",
           item->kind == SW_NAVY_APP ? "apps" : "tests", item->name);
  int nanos_idx = find_test(st, path);
  return nanos_idx >= 0 ? nanos_idx : idx;
}

static void select_interactive_software(ShellState *st, int idx) {
  int selected = nanos_counterpart_index(st, idx);
  if (selected != idx) {
    printf("selected navy app will run via nanos-lite: %s\n", st->software[selected].path);
  }
  st->selected = selected;
}

static void save_config(const ShellState *st) {
  char path[PATH_MAX];
  char dir[PATH_MAX];
  config_path(path, sizeof(path));
  snprintf(dir, sizeof(dir), "%s/build/busy-box-nano", root);
  if (mkdir_p(dir) != 0) {
    return;
  }

  FILE *fp = fopen(path, "w");
  if (fp == NULL) {
    perror(path);
    return;
  }
  const SoftwareItem *test = selected_test(st);
  fprintf(fp, "test=%s\n", test ? test->path : "");
  fprintf(fp, "arch=%s\n", selected_arch(st)->name);
  if (st->last_arch >= 0 && st->last_arch < arch_count()) {
    fprintf(fp, "last_arch=%s\n", arches[st->last_arch].name);
  }
  fprintf(fp, "batch=%d\n", st->batch ? 1 : 0);
  fprintf(fp, "rebuild=%d\n", st->rebuild ? 1 : 0);
  fprintf(fp, "mainargs=%s\n", st->mainargs);
  fclose(fp);
}

static void load_config(ShellState *st) {
  char path[PATH_MAX];
  config_path(path, sizeof(path));
  FILE *fp = fopen(path, "r");
  if (fp == NULL) {
    return;
  }

  char line[MAX_LINE];
  while (fgets(line, sizeof(line), fp) != NULL) {
    line[strcspn(line, "\r\n")] = '\0';
    char *eq = strchr(line, '=');
    if (eq == NULL) {
      continue;
    }
    *eq = '\0';
    const char *key = line;
    const char *value = eq + 1;
    if (strcmp(key, "test") == 0) {
      int idx = find_test(st, value);
      if (idx >= 0) {
        st->selected = idx;
      }
    } else if (strcmp(key, "arch") == 0) {
      int idx = find_arch(value);
      if (idx >= 0) {
        st->arch = idx;
      }
    } else if (strcmp(key, "last_arch") == 0) {
      int idx = find_arch(value);
      if (idx >= 0) {
        st->last_arch = idx;
      }
    } else if (strcmp(key, "batch") == 0) {
      st->batch = atoi(value) != 0;
    } else if (strcmp(key, "rebuild") == 0) {
      st->rebuild = atoi(value) != 0;
    } else if (strcmp(key, "mainargs") == 0) {
      strncpy(st->mainargs, value, sizeof(st->mainargs) - 1);
      st->mainargs[sizeof(st->mainargs) - 1] = '\0';
    }
  }
  fclose(fp);
}

static const char *effective_mainargs(const ShellState *st, const SoftwareItem *test) {
  return test && test->mainargs[0] ? test->mainargs : st->mainargs;
}

static int is_am_image_kind(SoftwareKind kind) {
  return kind == SW_CPU_TEST || kind == SW_AM_TEST || kind == SW_ALU_TEST;
}

static int is_navy_kind(SoftwareKind kind) {
  return kind == SW_NAVY_TEST || kind == SW_NAVY_APP;
}

static int is_nanos_kind(SoftwareKind kind) {
  return kind == SW_NANOS_DEFAULT || kind == SW_NANOS_NAVY_TEST || kind == SW_NANOS_NAVY_APP;
}

static const char *navy_isa(const CpuArch *arch) {
  return strncmp(arch->am_arch, "riscv32", 7) == 0 ? "riscv32" : "riscv64";
}

static const char *navy_subdir_for_kind(SoftwareKind kind) {
  if (kind == SW_NAVY_APP || kind == SW_NANOS_NAVY_APP) {
    return "apps";
  }
  if (kind == SW_NAVY_TEST || kind == SW_NANOS_NAVY_TEST) {
    return "tests";
  }
  return NULL;
}

static void image_paths(const ShellState *st, const SoftwareItem *test,
                        char *dir, size_t dir_size,
                        char *mk, size_t mk_size, char *bin, size_t bin_size,
                        char *elf, size_t elf_size, char *log, size_t log_size) {
  const CpuArch *arch = selected_arch(st);
  snprintf(dir, dir_size, "%s/build/busy-box-nano/software/%s/%s", root, arch->name, test->path);
  snprintf(mk, mk_size, "%s/zcu102-software.mk", dir);
  snprintf(bin, bin_size, "%s/build/%s-%s.bin", dir, test->make_name, arch->am_arch);
  snprintf(elf, elf_size, "%s/build/%s-%s.elf", dir, test->make_name, arch->am_arch);
  snprintf(log, log_size, "%s/nemu-log.txt", dir);
}

static int ensure_links(const SoftwareItem *test, const char *dir) {
  char cmd[PATH_MAX * 3];
  if (test->kind == SW_CPU_TEST) {
    snprintf(cmd, sizeof(cmd),
             "ln -sfn '%s/nemu-src/am-kernels/tests/cpu-tests/tests' '%s/tests' && "
             "ln -sfn '%s/nemu-src/am-kernels/tests/cpu-tests/include' '%s/include'",
             root, dir, root, dir);
  } else {
    if (test->kind == SW_AM_TEST) {
      snprintf(cmd, sizeof(cmd),
               "ln -sfn '%s/nemu-src/am-kernels/tests/am-tests/src' '%s/src' && "
               "ln -sfn '%s/nemu-src/am-kernels/tests/am-tests/include' '%s/include'",
               root, dir, root, dir);
    } else {
      snprintf(cmd, sizeof(cmd),
               "ln -sfn '%s/nemu-src/am-kernels/tests/alu-tests/gen_alu_test.c' '%s/gen_alu_test.c' && "
               "mkdir -p '%s/build'",
               root, dir, dir);
    }
  }
  return run_cmd(cmd);
}

static int write_test_makefile(const SoftwareItem *test, const char *dir, const char *mk) {
  if (mkdir_p(dir) != 0) {
    return -1;
  }
  if (ensure_links(test, dir) != 0) {
    return -1;
  }

  FILE *fp = fopen(mk, "w");
  if (fp == NULL) {
    perror(mk);
    return -1;
  }
  fprintf(fp, "# Generated by busy-box-nano\n");
  fprintf(fp, "NAME = %s\n", test->make_name);
  if (test->kind == SW_CPU_TEST) {
    fprintf(fp, "SRCS = tests/%s.c\n", test->name);
    fprintf(fp, "INC_PATH += include\n");
    fprintf(fp, "CFLAGS += -DZCU102_RUNTIME_GUEST=1 -include %s/include/zcu102_runtime_overrides.h\n", root);
  } else {
    if (test->kind == SW_AM_TEST) {
      fprintf(fp, "SRCS = $(shell find src/ -name \"*.[cS]\")\n");
    } else {
      fprintf(fp, "SRCS = build/alu_test.c\n");
      fprintf(fp, "GENERATOR = build/gen_alu_test\n");
      fprintf(fp, "$(GENERATOR): gen_alu_test.c\n");
      fprintf(fp, "\tgcc -O2 -Wall -Werror $^ -o $@\n");
      fprintf(fp, "$(SRCS): $(GENERATOR)\n");
      fprintf(fp, "\t$^ > $@\n");
    }
  }
  fprintf(fp, "include %s/nemu-src/abstract-machine/Makefile\n", root);
  fclose(fp);
  return 0;
}

static int prepare_selected(const ShellState *st, const char *dir, const char *mk, const SoftwareItem *test) {
  int arch_changed = st->last_arch >= 0 && st->last_arch != st->arch;
  if (st->rebuild || arch_changed) {
    if (arch_changed) {
      printf("arch changed: %s -> %s, rebuild selected image\n",
             arches[st->last_arch].name, selected_arch(st)->name);
    }
    char rm_cmd[PATH_MAX + 16];
    snprintf(rm_cmd, sizeof(rm_cmd), "rm -rf '%s'", dir);
    if (run_cmd(rm_cmd) != 0) {
      return 1;
    }
  }

  if (write_test_makefile(test, dir, mk) != 0) {
    return 1;
  }
  return 0;
}

static int clean_if_needed(const ShellState *st, const char *path, int force) {
  if (!st->rebuild && !force) {
    return 0;
  }

  char cmd[PATH_MAX + NAME_MAX + 64];
  int len = snprintf(cmd, sizeof(cmd), "rm -rf '%s'", path);
  if (len < 0 || (size_t)len >= sizeof(cmd)) {
    fprintf(stderr, "path too long for rm command: %s\n", path);
    return 1;
  }
  return run_cmd(cmd);
}

static int build_navy_program(ShellState *st, const SoftwareItem *test) {
  const CpuArch *arch = selected_arch(st);
  int arch_changed = st->last_arch >= 0 && st->last_arch != st->arch;
  const char *subdir = navy_subdir_for_kind(test->kind);
  if (subdir == NULL) {
    fprintf(stderr, "not a navy program: %s\n", test->path);
    return 2;
  }
  char app_dir[PATH_MAX];
  snprintf(app_dir, sizeof(app_dir), "%s/nemu-src/navy-apps/%s/%s", root, subdir, test->name);

  if (arch_changed) {
    printf("arch changed: %s -> %s, rebuild navy app\n",
           arches[st->last_arch].name, selected_arch(st)->name);
  }

  if (st->rebuild || arch_changed) {
    char build_dir[PATH_MAX + 16];
    int len = snprintf(build_dir, sizeof(build_dir), "%s/build", app_dir);
    if (len < 0 || (size_t)len >= sizeof(build_dir)) {
      fprintf(stderr, "path too long: %s/build\n", app_dir);
      return 1;
    }
    if (clean_if_needed(st, build_dir, arch_changed) != 0) {
      return 1;
    }
  }

  char cmd[PATH_MAX * 6 + 256];
  snprintf(cmd, sizeof(cmd),
           "AM_HOME='%s/nemu-src/abstract-machine' "
           "NAVY_HOME='%s/nemu-src/navy-apps' "
           "NEMU_HOME='%s/nemu-src/nemu' "
           "NPC_HOME='%s/../npc' "
           "make -C '%s' ISA=%s install "
           "AM_HOME='%s/nemu-src/abstract-machine' "
           "NAVY_HOME='%s/nemu-src/navy-apps'",
           root, root, root, root, app_dir, navy_isa(arch), root, root);
  int code = run_cmd(cmd);
  if (code == 0) {
    printf("installed: %s/nemu-src/navy-apps/fsimg/bin/%s\n", root, test->make_name);
    st->last_arch = st->arch;
    save_config(st);
  }
  return code;
}

static int prepare_nanos_program(ShellState *st, const SoftwareItem *test) {
  const CpuArch *arch = selected_arch(st);
  int arch_changed = st->last_arch >= 0 && st->last_arch != st->arch;
  if (test->kind == SW_NANOS_DEFAULT) {
    if (arch_changed) {
      printf("arch changed: %s -> %s, rebuild nanos-lite\n",
             arches[st->last_arch].name, selected_arch(st)->name);
    }
    if (st->rebuild || arch_changed) {
      char build_dir[PATH_MAX];
      snprintf(build_dir, sizeof(build_dir), "%s/nemu-src/nanos-lite/build", root);
      if (clean_if_needed(st, build_dir, arch_changed) != 0) {
        return 1;
      }
    }

    char cmd[PATH_MAX * 6 + 256];
    snprintf(cmd, sizeof(cmd),
             "AM_HOME='%s/nemu-src/abstract-machine' "
             "NAVY_HOME='%s/nemu-src/navy-apps' "
             "NEMU_HOME='%s/nemu-src/nemu' "
             "NPC_HOME='%s/../npc' "
             "make -C '%s/nemu-src/nanos-lite' ARCH=%s update "
             "AM_HOME='%s/nemu-src/abstract-machine' "
             "NAVY_HOME='%s/nemu-src/navy-apps'",
             root, root, root, root, root, arch->am_arch, root, root);
    return run_cmd(cmd);
  }

  if (arch_changed) {
    printf("arch changed: %s -> %s, rebuild nanos-lite and selected navy app\n",
           arches[st->last_arch].name, selected_arch(st)->name);
  }

  if (st->rebuild || arch_changed) {
    char build_dir[PATH_MAX];
    snprintf(build_dir, sizeof(build_dir), "%s/nemu-src/nanos-lite/build", root);
    if (clean_if_needed(st, build_dir, arch_changed) != 0) {
      return 1;
    }
    const char *subdir = navy_subdir_for_kind(test->kind);
    if (subdir != NULL) {
      char navy_build_dir[PATH_MAX + 64];
      int len = snprintf(navy_build_dir, sizeof(navy_build_dir),
                         "%s/nemu-src/navy-apps/%s/%s/build", root, subdir, test->name);
      if (len < 0 || (size_t)len >= sizeof(navy_build_dir)) {
        fprintf(stderr, "path too long for navy build dir: %s\n", test->path);
        return 1;
      }
      char rm_cmd[PATH_MAX + 192];
      len = snprintf(rm_cmd, sizeof(rm_cmd), "rm -rf '%s'", navy_build_dir);
      if (len < 0 || (size_t)len >= sizeof(rm_cmd)) {
        fprintf(stderr, "path too long for rm command: %s\n", navy_build_dir);
        return 1;
      }
      if (run_cmd(rm_cmd) != 0) {
        return 1;
      }
    }
  }

  char cmd[PATH_MAX * 6 + 256];
  snprintf(cmd, sizeof(cmd),
           "AM_HOME='%s/nemu-src/abstract-machine' "
           "NAVY_HOME='%s/nemu-src/navy-apps' "
           "NEMU_HOME='%s/nemu-src/nemu' "
           "NPC_HOME='%s/../npc' "
           "make -C '%s/nemu-src/nanos-lite' ARCH=%s save PROG='%s' "
           "AM_HOME='%s/nemu-src/abstract-machine' "
           "NAVY_HOME='%s/nemu-src/navy-apps'",
           root, root, root, root, root, arch->am_arch, test->make_name, root, root);
  return run_cmd(cmd);
}

static int build_nanos_program(ShellState *st, const SoftwareItem *test) {
  const CpuArch *arch = selected_arch(st);
  int code = prepare_nanos_program(st, test);
  if (code != 0) {
    return code;
  }

  char cmd[PATH_MAX * 6 + 256];
  if (test->kind == SW_NANOS_DEFAULT) {
    snprintf(cmd, sizeof(cmd),
             "AM_HOME='%s/nemu-src/abstract-machine' "
             "NAVY_HOME='%s/nemu-src/navy-apps' "
             "NEMU_HOME='%s/nemu-src/nemu' "
             "NPC_HOME='%s/../npc' "
             "make -C '%s/nemu-src/nanos-lite' ARCH=%s image "
             "AM_HOME='%s/nemu-src/abstract-machine' "
             "NEMU_HOME='%s/nemu-src/nemu' "
             "NPC_HOME='%s/../npc' "
             "NAVY_HOME='%s/nemu-src/navy-apps'",
             root, root, root, root, root, arch->am_arch, root, root, root, root);
  } else {
    snprintf(cmd, sizeof(cmd),
             "AM_HOME='%s/nemu-src/abstract-machine' "
             "NAVY_HOME='%s/nemu-src/navy-apps' "
             "NEMU_HOME='%s/nemu-src/nemu' "
             "NPC_HOME='%s/../npc' "
             "make -C '%s/nemu-src/nanos-lite' ARCH=%s image PROG='%s' "
             "AM_HOME='%s/nemu-src/abstract-machine' "
             "NEMU_HOME='%s/nemu-src/nemu' "
             "NPC_HOME='%s/../npc' "
             "NAVY_HOME='%s/nemu-src/navy-apps'",
             root, root, root, root, root, arch->am_arch, test->make_name, root, root, root, root);
  }
  code = run_cmd(cmd);
  if (code == 0) {
    printf("image: %s/nemu-src/nanos-lite/build/nanos-lite-%s.bin\n", root, arch->am_arch);
    printf("elf:   %s/nemu-src/nanos-lite/build/nanos-lite-%s.elf\n", root, arch->am_arch);
    write_nanos_stamp(st, test);
    st->last_arch = st->arch;
    save_config(st);
  }
  return code;
}

static int read_nanos_stamp(const ShellState *st, char *buf, size_t size) {
  char path[PATH_MAX];
  nanos_stamp_path(st, path, sizeof(path));
  FILE *fp = fopen(path, "r");
  if (fp == NULL) {
    return -1;
  }
  if (fgets(buf, size, fp) == NULL) {
    fclose(fp);
    return -1;
  }
  fclose(fp);
  buf[strcspn(buf, "\r\n")] = '\0';
  return 0;
}

static int write_nanos_stamp(const ShellState *st, const SoftwareItem *test) {
  char path[PATH_MAX], dir[PATH_MAX], value[512];
  nanos_stamp_path(st, path, sizeof(path));
  snprintf(dir, sizeof(dir), "%s/build/busy-box-nano", root);
  if (mkdir_p(dir) != 0) {
    return -1;
  }
  FILE *fp = fopen(path, "w");
  if (fp == NULL) {
    perror(path);
    return -1;
  }
  snprintf(value, sizeof(value), "%s|%s|%s",
           selected_arch(st)->name, test->path, test->make_name);
  fprintf(fp, "%s\n", value);
  fclose(fp);
  return 0;
}

static int nanos_image_current(const ShellState *st, const SoftwareItem *test,
                               const char *bin, const char *elf) {
  if (st->rebuild || !is_regular_file(bin) || !is_regular_file(elf)) {
    return 0;
  }
  if (st->last_arch >= 0 && st->last_arch != st->arch) {
    return 0;
  }

  char expected[512], actual[512];
  snprintf(expected, sizeof(expected), "%s|%s|%s",
           selected_arch(st)->name, test->path, test->make_name);
  return read_nanos_stamp(st, actual, sizeof(actual)) == 0 &&
         strcmp(expected, actual) == 0;
}

static int run_nanos_program(ShellState *st, const SoftwareItem *test, int auto_run) {
  const CpuArch *arch = selected_arch(st);
  char bin[PATH_MAX], elf[PATH_MAX], log[PATH_MAX];
  snprintf(bin, sizeof(bin), "%s/nemu-src/nanos-lite/build/nanos-lite-%s.bin", root, arch->am_arch);
  snprintf(elf, sizeof(elf), "%s/nemu-src/nanos-lite/build/nanos-lite-%s.elf", root, arch->am_arch);
  snprintf(log, sizeof(log), "%s/nemu-src/nanos-lite/build/nemu-log.txt", root);

  if (!arch->use_npc) {
    if (!nanos_image_current(st, test, bin, elf)) {
      int code = build_nanos_program(st, test);
      if (code != 0) {
        return code;
      }
    } else {
      printf("using existing nanos-lite image: %s\n", bin);
    }

    char cmd[PATH_MAX * 4 + 64];
    snprintf(cmd, sizeof(cmd), "'%s/build/bin/%s' --log='%s' -e '%s' %s '%s'",
             root, arch->ps_bin, log, elf, auto_run ? "--batch" : "", bin);
    return run_cmd(cmd);
  }

  int code = prepare_nanos_program(st, test);
  if (code != 0) {
    return code;
  }

  const char *target = auto_run ? "run-npc-bat" : "run-npc";
  char cmd[PATH_MAX * 6 + 256];
  if (test->kind == SW_NANOS_DEFAULT) {
    snprintf(cmd, sizeof(cmd),
             "AM_HOME='%s/nemu-src/abstract-machine' "
             "NAVY_HOME='%s/nemu-src/navy-apps' "
             "NEMU_HOME='%s/nemu-src/nemu' "
             "NPC_HOME='%s/../npc' "
             "make -C '%s/nemu-src/nanos-lite' ARCH=%s %s "
             "AM_HOME='%s/nemu-src/abstract-machine' "
             "NEMU_HOME='%s/nemu-src/nemu' "
             "NPC_HOME='%s/../npc' "
             "NAVY_HOME='%s/nemu-src/navy-apps'",
             root, root, root, root, root, arch->am_arch, target, root, root, root, root);
  } else {
    snprintf(cmd, sizeof(cmd),
             "AM_HOME='%s/nemu-src/abstract-machine' "
             "NAVY_HOME='%s/nemu-src/navy-apps' "
             "NEMU_HOME='%s/nemu-src/nemu' "
             "NPC_HOME='%s/../npc' "
             "make -C '%s/nemu-src/nanos-lite' ARCH=%s %s PROG='%s' "
             "AM_HOME='%s/nemu-src/abstract-machine' "
             "NEMU_HOME='%s/nemu-src/nemu' "
             "NPC_HOME='%s/../npc' "
             "NAVY_HOME='%s/nemu-src/navy-apps'",
             root, root, root, root, root, arch->am_arch, target, test->make_name, root, root, root, root);
  }
  code = run_cmd(cmd);
  if (code == 0) {
    st->last_arch = st->arch;
    save_config(st);
  }
  return code;
}

static int build_selected(const ShellState *st) {
  const SoftwareItem *test = selected_test(st);
  if (test == NULL) {
    puts("no test selected");
    return 2;
  }

  if (is_navy_kind(test->kind)) {
    return build_navy_program((ShellState *)st, test);
  }
  if (is_nanos_kind(test->kind)) {
    return build_nanos_program((ShellState *)st, test);
  }
  if (!is_am_image_kind(test->kind)) {
    fprintf(stderr, "unsupported software kind for build: %s\n", test->path);
    return 2;
  }

  char dir[PATH_MAX], mk[PATH_MAX], bin[PATH_MAX], elf[PATH_MAX], log[PATH_MAX];
  const CpuArch *arch = selected_arch(st);
  image_paths(st, test, dir, sizeof(dir), mk, sizeof(mk), bin, sizeof(bin), elf, sizeof(elf), log, sizeof(log));

  if (prepare_selected(st, dir, mk, test) != 0) {
    return 1;
  }

  char cmd[PATH_MAX * 7 + 512];
  snprintf(cmd, sizeof(cmd),
           "cd '%s' && AM_HOME='%s/nemu-src/abstract-machine' "
           "NEMU_HOME='%s/nemu-src/nemu' "
           "NPC_HOME='%s/../npc' "
           "make -s -f '%s' insert-arg ARCH=%s "
           "AM_HOME='%s/nemu-src/abstract-machine' "
           "NEMU_HOME='%s/nemu-src/nemu' "
           "NPC_HOME='%s/../npc' mainargs='%s'",
           dir, root, root, root, mk, arch->am_arch, root, root, root, effective_mainargs(st, test));
  int code = run_cmd(cmd);
  if (code == 0) {
    printf("image: %s\n", bin);
    printf("elf:   %s\n", elf);
    ((ShellState *)st)->last_arch = st->arch;
    save_config(st);
  }
  return code;
}

static int run_selected(ShellState *st, int auto_run) {
  const SoftwareItem *test = selected_test(st);
  if (test == NULL) {
    puts("no test selected");
    return 2;
  }

  if (is_navy_kind(test->kind)) {
    return run_nanos_program(st, test, auto_run);
  }
  if (is_nanos_kind(test->kind)) {
    return run_nanos_program(st, test, auto_run);
  }
  if (!is_am_image_kind(test->kind)) {
    fprintf(stderr, "unsupported software kind for run: %s\n", test->path);
    return 2;
  }

  char dir[PATH_MAX], mk[PATH_MAX], bin[PATH_MAX], elf[PATH_MAX], log[PATH_MAX];
  const CpuArch *arch = selected_arch(st);
  image_paths(st, test, dir, sizeof(dir), mk, sizeof(mk), bin, sizeof(bin), elf, sizeof(elf), log, sizeof(log));

  if (arch->use_npc) {
    if (prepare_selected(st, dir, mk, test) != 0) {
      return 1;
    }
    const char *target = auto_run ? "run-npc-bat" : "run-npc";
    char cmd[PATH_MAX * 7 + 512];
    snprintf(cmd, sizeof(cmd),
             "cd '%s' && AM_HOME='%s/nemu-src/abstract-machine' "
             "NEMU_HOME='%s/nemu-src/nemu' "
             "NPC_HOME='%s/../npc' "
             "make -f '%s' %s ARCH=%s "
             "AM_HOME='%s/nemu-src/abstract-machine' "
             "NEMU_HOME='%s/nemu-src/nemu' "
             "NPC_HOME='%s/../npc' mainargs='%s'",
             dir, root, root, root, mk, target, arch->am_arch, root, root, root, effective_mainargs(st, test));
    int code = run_cmd(cmd);
    if (code == 0) {
      st->last_arch = st->arch;
      save_config(st);
    }
    return code;
  }

  if (build_selected(st) != 0) {
    return 1;
  }

  char cmd[PATH_MAX * 4 + 64];
  snprintf(cmd, sizeof(cmd), "'%s/build/bin/%s' --log='%s' -e '%s' %s '%s'",
           root, arch->ps_bin, log, elf, auto_run ? "--batch" : "", bin);
  return run_cmd(cmd);
}

static void configure_options(ShellState *st) {
  char line[MAX_LINE];
  printf("rebuild before run [%s] (y/n): ", st->rebuild ? "y" : "n");
  fflush(stdout);
  if (fgets(line, sizeof(line), stdin) != NULL && (line[0] == 'y' || line[0] == 'Y')) {
    st->rebuild = 1;
  } else if (line[0] == 'n' || line[0] == 'N') {
    st->rebuild = 0;
  }

  printf("mainargs [%s]: ", st->mainargs);
  fflush(stdout);
  if (fgets(line, sizeof(line), stdin) != NULL) {
    line[strcspn(line, "\r\n")] = '\0';
    if (line[0] != '\0') {
      strncpy(st->mainargs, line, sizeof(st->mainargs) - 1);
      st->mainargs[sizeof(st->mainargs) - 1] = '\0';
    }
  }
  save_config(st);
}

static const char *software_kind_name(SoftwareKind kind) {
  switch (kind) {
    case SW_CPU_TEST: return "cpu-test";
    case SW_AM_TEST: return "am-test";
    case SW_ALU_TEST: return "alu-test";
    case SW_NAVY_TEST: return "navy-test";
    case SW_NAVY_APP: return "navy-app";
    case SW_NANOS_DEFAULT: return "nanos-lite";
    case SW_NANOS_NAVY_TEST: return "nanos-lite + navy-test";
    case SW_NANOS_NAVY_APP: return "nanos-lite + navy-app";
    default: return "unknown";
  }
}

static void print_software_status(const SoftwareItem *test) {
  if (test == NULL) {
    puts("  software: (none)");
    return;
  }

  printf("  software: %s\n", test->path);
  printf("  kind:     %s\n", software_kind_name(test->kind));
  if (test->kind == SW_NANOS_DEFAULT) {
    puts("  runtime:  nanos-lite");
    puts("  app:      first file in current ramdisk");
  } else if (test->kind == SW_NANOS_NAVY_TEST || test->kind == SW_NANOS_NAVY_APP) {
    const char *subdir = navy_subdir_for_kind(test->kind);
    printf("  runtime:  nanos-lite\n");
    printf("  app:      navy-apps/%s/%s -> /bin/%s\n",
           subdir ? subdir : "?", test->name, test->make_name);
  } else if (test->kind == SW_NAVY_TEST || test->kind == SW_NAVY_APP) {
    const char *subdir = navy_subdir_for_kind(test->kind);
    printf("  app:      navy-apps/%s/%s -> /bin/%s\n",
           subdir ? subdir : "?", test->name, test->make_name);
    puts("  run via:  nanos-lite");
  }
}

static void show_status(const ShellState *st) {
  const SoftwareItem *test = selected_test(st);
  int arch_changed = st->last_arch >= 0 && st->last_arch != st->arch;
  puts("\nConfiguration:");
  print_software_status(test);
  printf("  arch:    %s\n", selected_arch(st)->name);
  printf("  last:    %s\n", st->last_arch >= 0 ? arches[st->last_arch].name : "(none)");
  printf("  dirty:   %s\n", arch_changed ? "yes (arch changed, rebuild on next build/run)" : "no");
  printf("  rebuild: %s\n", st->rebuild ? "yes" : "no");
  printf("  args:    %s\n", effective_mainargs(st, test)[0] ? effective_mainargs(st, test) : "(none)");
}

static int parse_arch_args(ShellState *st, int argc, char **argv, int start) {
  for (int i = start; i < argc; i++) {
    const char *value = NULL;
    if (strcmp(argv[i], "--arch") == 0) {
      if (i + 1 >= argc) {
        fputs("missing value for --arch\n", stderr);
        return -1;
      }
      value = argv[++i];
    } else if (strncmp(argv[i], "--arch=", 7) == 0) {
      value = argv[i] + 7;
    } else if (strcmp(argv[i], "--batch") == 0) {
      st->batch = 1;
      continue;
    } else if (strcmp(argv[i], "--no-batch") == 0) {
      st->batch = 0;
      continue;
    } else if (strcmp(argv[i], "--rebuild") == 0) {
      st->rebuild = 1;
      continue;
    } else if (strcmp(argv[i], "--no-rebuild") == 0) {
      st->rebuild = 0;
      continue;
    } else if (strcmp(argv[i], "--mainargs") == 0) {
      if (i + 1 >= argc) {
        fputs("missing value for --mainargs\n", stderr);
        return -1;
      }
      strncpy(st->mainargs, argv[++i], sizeof(st->mainargs) - 1);
      st->mainargs[sizeof(st->mainargs) - 1] = '\0';
      continue;
    } else if (strncmp(argv[i], "--mainargs=", 11) == 0) {
      strncpy(st->mainargs, argv[i] + 11, sizeof(st->mainargs) - 1);
      st->mainargs[sizeof(st->mainargs) - 1] = '\0';
      continue;
    } else {
      fprintf(stderr, "unknown option: %s\n", argv[i]);
      return -1;
    }

    int idx = find_arch(value);
    if (idx < 0) {
      fprintf(stderr, "unknown arch: %s\n", value);
      return -1;
    }
    st->arch = idx;
  }
  return 0;
}

static int parse_arg_run(ShellState *st, const char *name) {
  int idx = find_test(st, name);
  if (idx >= 0) {
    st->selected = nanos_counterpart_index(st, idx);
    return run_selected(st, st->batch);
  }
  fprintf(stderr, "unknown software: %s\n", name);
  return 2;
}

int main(int argc, char **argv) {
  ShellState st;
  memset(&st, 0, sizeof(st));
  st.selected = -1;
  st.arch = 0;
  st.last_arch = -1;
  st.batch = 1;

  if (discover_software(&st) != 0) {
    fprintf(stderr, "failed to discover software\n");
    return 2;
  }

  if (argc == 2 && strcmp(argv[1], "--list") == 0) {
    print_tests(&st);
    return 0;
  }
  load_config(&st);

  if (argc == 2 && strcmp(argv[1], "--list-arches") == 0) {
    print_arches(&st);
    return 0;
  }
  if (argc >= 3 && strcmp(argv[1], "--run") == 0) {
    st.batch = 1;
    if (parse_arch_args(&st, argc, argv, 3) != 0) {
      return 2;
    }
    int code = parse_arg_run(&st, argv[2]);
    save_config(&st);
    return code;
  }
  if (argc >= 3 && strcmp(argv[1], "--build") == 0) {
    if (parse_arch_args(&st, argc, argv, 3) != 0) {
      return 2;
    }
    int idx = find_test(&st, argv[2]);
    if (idx >= 0) {
      st.selected = idx;
      int code = build_selected(&st);
      save_config(&st);
      return code;
    }
    fprintf(stderr, "unknown software: %s\n", argv[2]);
    return 2;
  }

  puts("busy-box-nano");
  puts("PS-side software launcher for NEMU SDB");

  for (;;) {
    show_status(&st);
    puts("\nMenu:");
    puts("  1. Select arch");
    puts("  2. Select software");
    puts("  3. Options");
    puts("  4. Build image only");
    puts("  5. Run selected software");
    puts("  6. Open NEMU SDB");
    puts("  7. List software");
    puts("  8. Quit");
    printf("choice> ");
    fflush(stdout);

    char line[MAX_LINE];
    if (fgets(line, sizeof(line), stdin) == NULL) {
      putchar('\n');
      return 0;
    }
    line[strcspn(line, "\r\n")] = '\0';
    if (strcmp(line, "q") == 0 || strcmp(line, "quit") == 0 || strcmp(line, "exit") == 0) {
      return 0;
    }

    switch (atoi(line)) {
      case 1: choose_arch(&st); break;
      case 2: choose_test(&st); break;
      case 3: configure_options(&st); break;
      case 4: build_selected(&st); break;
      case 5: run_selected(&st, 1); break;
      case 6:
        puts("NEMU SDB will stop at the debugger prompt. Type 'c' in '(nemu)' to start the guest.");
        run_selected(&st, 0);
        break;
      case 7: print_tests(&st); break;
      case 8: return 0;
      default: puts("invalid choice"); break;
    }
  }
}
