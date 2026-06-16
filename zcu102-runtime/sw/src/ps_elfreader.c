#include "../../nemu-src/nemu/src/monitor/sdb/sdb.h"

#include <elf.h>
#include <stdlib.h>

#define MAX_FUNC_NUM 128
#define MAX_FTRACE_DEPTH 128

typedef struct {
  char name[32];
  uint64_t entry_addr;
  uint64_t exit_addr;
  uint64_t size;
} FuncInfo;

typedef struct {
  char func_name[32];
  int type;
  int depth;
  uint64_t pc;
  bool active;
} FtraceEntry;

static FuncInfo func_table[MAX_FUNC_NUM];
static int func_count = 0;
static FtraceEntry ftrace_stack[MAX_FTRACE_DEPTH];
static int ftrace_stack_idx = 0;
static int deepest_ftrace_depth = 0;

static bool elf_verbose(void) {
  const char *value = getenv("ZCU102_ELF_VERBOSE");
  return value != NULL && value[0] != '\0' && strcmp(value, "0") != 0;
}

static int read_at(FILE *file, long offset, void *buf, size_t size, const char *what) {
  if (fseek(file, offset, SEEK_SET) != 0) {
    printf("Failed to seek %s\n", what);
    return -1;
  }
  if (fread(buf, 1, size, file) != size) {
    printf("Failed to read %s\n", what);
    return -1;
  }
  return 0;
}

static void reset_symbols(void) {
  func_count = 0;
  ftrace_stack_idx = 0;
  deepest_ftrace_depth = 0;
}

static void add_func_symbol(const char *name, uint64_t value, uint64_t size) {
  if (func_count >= MAX_FUNC_NUM || name == NULL || name[0] == '\0' || size == 0) {
    return;
  }

  FuncInfo *func = &func_table[func_count++];
  snprintf(func->name, sizeof(func->name), "%s", name);
  func->entry_addr = value;
  func->size = size;
  func->exit_addr = value + size - 4;

  if (elf_verbose()) {
    printf("%-4d %-20s 0x%016lx 0x%016lx %-10lu\n",
           func_count - 1, func->name, func->entry_addr, func->exit_addr, func->size);
  }
}

static void print_func_table_header(void) {
  puts("\n=== Function Symbols ===");
  printf("%-4s %-20s %-18s %-18s %-10s\n", "Idx", "Name", "Entry", "Exit", "Size");
  puts("--------------------------------------------------------------------------------");
}

static void analyze_elf64(FILE *file, const Elf64_Ehdr *ehdr) {
  if (elf_verbose()) {
    printf("ELF Type: %u\n", ehdr->e_type);
    printf("Entry point: 0x%lx\n", ehdr->e_entry);
    printf("Section header offset: 0x%lx\n", ehdr->e_shoff);
    printf("Number of section headers: %u\n", ehdr->e_shnum);
  }

  Elf64_Shdr *shdr_table = malloc(sizeof(Elf64_Shdr) * ehdr->e_shnum);
  if (shdr_table == NULL) {
    puts("Failed to allocate section header table");
    return;
  }
  if (read_at(file, ehdr->e_shoff, shdr_table,
              sizeof(Elf64_Shdr) * ehdr->e_shnum, "section header table") != 0) {
    free(shdr_table);
    return;
  }

  if (ehdr->e_shstrndx >= ehdr->e_shnum) {
    puts("Invalid section header string table index");
    free(shdr_table);
    return;
  }

  Elf64_Shdr *shstrtab_hdr = &shdr_table[ehdr->e_shstrndx];
  char *shstrtab = malloc(shstrtab_hdr->sh_size);
  if (shstrtab == NULL) {
    puts("Failed to allocate section header string table");
    free(shdr_table);
    return;
  }
  if (read_at(file, shstrtab_hdr->sh_offset, shstrtab, shstrtab_hdr->sh_size,
              "section header string table") != 0) {
    free(shstrtab);
    free(shdr_table);
    return;
  }

  Elf64_Shdr *symtab_hdr = NULL;
  Elf64_Shdr *strtab_hdr = NULL;
  for (int i = 0; i < ehdr->e_shnum; i++) {
    const char *section_name = shstrtab + shdr_table[i].sh_name;
    if (strcmp(section_name, ".symtab") == 0) {
      symtab_hdr = &shdr_table[i];
    } else if (strcmp(section_name, ".strtab") == 0) {
      strtab_hdr = &shdr_table[i];
    }
  }

  if (symtab_hdr == NULL || strtab_hdr == NULL) {
    puts("Symbol table or string table not found");
    free(shstrtab);
    free(shdr_table);
    return;
  }

  Elf64_Sym *symtab = malloc(symtab_hdr->sh_size);
  char *strtab = malloc(strtab_hdr->sh_size);
  if (symtab == NULL || strtab == NULL) {
    puts("Failed to allocate symbol data");
    free(strtab);
    free(symtab);
    free(shstrtab);
    free(shdr_table);
    return;
  }
  if (read_at(file, symtab_hdr->sh_offset, symtab, symtab_hdr->sh_size, "symbol table") != 0 ||
      read_at(file, strtab_hdr->sh_offset, strtab, strtab_hdr->sh_size, "string table") != 0) {
    free(strtab);
    free(symtab);
    free(shstrtab);
    free(shdr_table);
    return;
  }

  if (elf_verbose()) {
    print_func_table_header();
  }
  int sym_num = symtab_hdr->sh_size / sizeof(Elf64_Sym);
  for (int i = 0; i < sym_num; i++) {
    Elf64_Sym *sym = &symtab[i];
    if (ELF64_ST_TYPE(sym->st_info) == STT_FUNC && sym->st_name < strtab_hdr->sh_size) {
      add_func_symbol(strtab + sym->st_name, sym->st_value, sym->st_size);
    }
  }
  if (elf_verbose()) {
    printf("\nTotal functions found: %d\n", func_count);
  }

  free(strtab);
  free(symtab);
  free(shstrtab);
  free(shdr_table);
}

static void analyze_elf32(FILE *file, const Elf32_Ehdr *ehdr) {
  if (elf_verbose()) {
    printf("ELF Type: %u\n", ehdr->e_type);
    printf("Entry point: 0x%08x\n", ehdr->e_entry);
    printf("Section header offset: 0x%08x\n", ehdr->e_shoff);
    printf("Number of section headers: %u\n", ehdr->e_shnum);
  }

  Elf32_Shdr *shdr_table = malloc(sizeof(Elf32_Shdr) * ehdr->e_shnum);
  if (shdr_table == NULL) {
    puts("Failed to allocate section header table");
    return;
  }
  if (read_at(file, ehdr->e_shoff, shdr_table,
              sizeof(Elf32_Shdr) * ehdr->e_shnum, "section header table") != 0) {
    free(shdr_table);
    return;
  }

  if (ehdr->e_shstrndx >= ehdr->e_shnum) {
    puts("Invalid section header string table index");
    free(shdr_table);
    return;
  }

  Elf32_Shdr *shstrtab_hdr = &shdr_table[ehdr->e_shstrndx];
  char *shstrtab = malloc(shstrtab_hdr->sh_size);
  if (shstrtab == NULL) {
    puts("Failed to allocate section header string table");
    free(shdr_table);
    return;
  }
  if (read_at(file, shstrtab_hdr->sh_offset, shstrtab, shstrtab_hdr->sh_size,
              "section header string table") != 0) {
    free(shstrtab);
    free(shdr_table);
    return;
  }

  Elf32_Shdr *symtab_hdr = NULL;
  Elf32_Shdr *strtab_hdr = NULL;
  for (int i = 0; i < ehdr->e_shnum; i++) {
    const char *section_name = shstrtab + shdr_table[i].sh_name;
    if (strcmp(section_name, ".symtab") == 0) {
      symtab_hdr = &shdr_table[i];
    } else if (strcmp(section_name, ".strtab") == 0) {
      strtab_hdr = &shdr_table[i];
    }
  }

  if (symtab_hdr == NULL || strtab_hdr == NULL) {
    puts("Symbol table or string table not found");
    free(shstrtab);
    free(shdr_table);
    return;
  }

  Elf32_Sym *symtab = malloc(symtab_hdr->sh_size);
  char *strtab = malloc(strtab_hdr->sh_size);
  if (symtab == NULL || strtab == NULL) {
    puts("Failed to allocate symbol data");
    free(strtab);
    free(symtab);
    free(shstrtab);
    free(shdr_table);
    return;
  }
  if (read_at(file, symtab_hdr->sh_offset, symtab, symtab_hdr->sh_size, "symbol table") != 0 ||
      read_at(file, strtab_hdr->sh_offset, strtab, strtab_hdr->sh_size, "string table") != 0) {
    free(strtab);
    free(symtab);
    free(shstrtab);
    free(shdr_table);
    return;
  }

  if (elf_verbose()) {
    print_func_table_header();
  }
  int sym_num = symtab_hdr->sh_size / sizeof(Elf32_Sym);
  for (int i = 0; i < sym_num; i++) {
    Elf32_Sym *sym = &symtab[i];
    if (ELF32_ST_TYPE(sym->st_info) == STT_FUNC && sym->st_name < strtab_hdr->sh_size) {
      add_func_symbol(strtab + sym->st_name, sym->st_value, sym->st_size);
    }
  }
  if (elf_verbose()) {
    printf("\nTotal functions found: %d\n", func_count);
  }

  free(strtab);
  free(symtab);
  free(shstrtab);
  free(shdr_table);
}

void analyze_elf(const char *elf_file) {
  FILE *file = fopen(elf_file, "rb");
  if (file == NULL) {
    fprintf(stderr, "Failed to open ELF file: %s\n", elf_file);
    return;
  }

  if (elf_verbose()) {
    printf("Analyzing ELF file: %s\n", elf_file);
  }
  reset_symbols();

  unsigned char ident[EI_NIDENT];
  if (read_at(file, 0, ident, sizeof(ident), "ELF ident") != 0) {
    fclose(file);
    return;
  }
  if (memcmp(ident, ELFMAG, SELFMAG) != 0) {
    puts("Not an ELF file");
    fclose(file);
    return;
  }

  if (ident[EI_CLASS] == ELFCLASS64) {
    Elf64_Ehdr ehdr;
    if (read_at(file, 0, &ehdr, sizeof(ehdr), "ELF64 header") == 0) {
      analyze_elf64(file, &ehdr);
    }
  } else if (ident[EI_CLASS] == ELFCLASS32) {
    Elf32_Ehdr ehdr;
    if (read_at(file, 0, &ehdr, sizeof(ehdr), "ELF32 header") == 0) {
      analyze_elf32(file, &ehdr);
    }
  } else {
    printf("Unsupported ELF class: %u\n", ident[EI_CLASS]);
  }

  fclose(file);
}

const char *get_func_name(uint64_t addr) {
  for (int i = 0; i < func_count; i++) {
    if (addr >= func_table[i].entry_addr &&
        addr < func_table[i].entry_addr + func_table[i].size) {
      return func_table[i].name;
    }
  }
  return NULL;
}

int is_func_entry(uint64_t addr) {
  for (int i = 0; i < func_count; i++) {
    if (addr == func_table[i].entry_addr) {
      return 1;
    }
  }
  return 0;
}

int is_func_exit(uint64_t addr) {
  for (int i = 0; i < func_count; i++) {
    if (addr >= func_table[i].exit_addr &&
        addr < func_table[i].entry_addr + func_table[i].size) {
      return 1;
    }
  }
  return 0;
}

int get_func_count(void) {
  return func_count;
}

void check_ftrace(uint64_t pc, uint32_t inst) {
  if (is_func_entry(pc)) {
    const char *func_name = get_func_name(pc);
    if (func_name != NULL && ftrace_stack_idx < MAX_FTRACE_DEPTH) {
      FtraceEntry *entry = &ftrace_stack[ftrace_stack_idx++];
      snprintf(entry->func_name, sizeof(entry->func_name), "%s", func_name);
      entry->type = 0;
      entry->depth = deepest_ftrace_depth++;
      entry->active = true;
      entry->pc = pc;
    }
  }

  uint32_t opcode = inst & 0x7f;
  uint32_t rd = (inst >> 7) & 0x1f;
  uint32_t funct3 = (inst >> 12) & 0x7;
  uint32_t rs1 = (inst >> 15) & 0x1f;
  int32_t imm = ((int32_t)inst) >> 20;
  if (opcode == 0x67 && funct3 == 0x0 && rd == 0 && rs1 == 1 && imm == 0) {
    const char *func_name = get_func_name(pc);
    if (func_name != NULL && ftrace_stack_idx < MAX_FTRACE_DEPTH) {
      FtraceEntry *entry = &ftrace_stack[ftrace_stack_idx++];
      snprintf(entry->func_name, sizeof(entry->func_name), "%s", func_name);
      entry->type = 1;
      entry->depth = deepest_ftrace_depth > 0 ? --deepest_ftrace_depth : 0;
      entry->active = true;
      entry->pc = pc;
    }
  }
}

void display_ftrace(void) {
  puts("==== Function Trace ====");
  for (int i = 0; i < ftrace_stack_idx; i++) {
    char indent[64] = {0};
    if (!ftrace_stack[i].active) {
      continue;
    }
    for (int j = 0; j < ftrace_stack[i].depth && strlen(indent) + 2 < sizeof(indent); j++) {
      strcat(indent, "  ");
    }
    printf("%s%s %s \t\t\t(0x%lx)\n", indent,
           ftrace_stack[i].type == 0 ? "-->" : "<--",
           ftrace_stack[i].func_name, ftrace_stack[i].pc);
  }
  puts("========================");
}
