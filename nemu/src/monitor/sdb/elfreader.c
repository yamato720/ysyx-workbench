#include "./sdb.h"
#include <elf.h>

#define MAX_FUNC_NUM 128

typedef struct func_info{
  char name[32];
  uint64_t entry_addr;
  uint64_t exit_addr;
  uint64_t size;
} func_info;

static func_info func_table[MAX_FUNC_NUM];
static int func_count = 0;

#define MAX_FTRACE_DEPTH 128

typedef struct ftrace
{
    char func_name[32];
    int type; // 0 for entry, 1 for exit
    int depth; // call depth for pretty printing
    uint64_t pc; // program counter of the function call/return
    bool active; // whether this ftrace entry is active (used for matching entries and exits)
}ftrace;

ftrace ftrace_stack[MAX_FTRACE_DEPTH];
int ftrace_stack_idx = 0;
int deepest_ftrace_depth = 0;



void analyze_elf(const char *elf_file) {
    FILE *file = fopen(elf_file, "rb");
    if (!file) {
        fprintf(stderr, "Failed to open ELF file: %s\n", elf_file);
        return;
    }
    printf("Analyzing ELF file: %s\n", elf_file);
    
    Elf64_Ehdr ehdr;
    if (fread(&ehdr, 1, sizeof(ehdr), file) != sizeof(ehdr)) {
        printf("Failed to read ELF header\n");
        fclose(file);
        return;
    }

    // 校验 ELF 魔数
    if (memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0) {
        printf("Not an ELF file\n");
        fclose(file);
        return;
    }
    
    printf("ELF Type: %u\n", ehdr.e_type);
    printf("Entry point: 0x%lx\n", ehdr.e_entry);
    printf("Section header offset: 0x%lx\n", ehdr.e_shoff);
    printf("Number of section headers: %u\n", ehdr.e_shnum);

    // 读取所有 section headers
    Elf64_Shdr *shdr_table = malloc(sizeof(Elf64_Shdr) * ehdr.e_shnum);
    fseek(file, ehdr.e_shoff, SEEK_SET);
    fread(shdr_table, sizeof(Elf64_Shdr), ehdr.e_shnum, file);

    // 找到 section header string table
    Elf64_Shdr *shstrtab_hdr = &shdr_table[ehdr.e_shstrndx];
    char *shstrtab = malloc(shstrtab_hdr->sh_size);
    fseek(file, shstrtab_hdr->sh_offset, SEEK_SET);
    fread(shstrtab, 1, shstrtab_hdr->sh_size, file);

    // 查找 .symtab 和 .strtab
    Elf64_Shdr *symtab_hdr = NULL;
    Elf64_Shdr *strtab_hdr = NULL;
    
    for (int i = 0; i < ehdr.e_shnum; i++) {
        char *section_name = shstrtab + shdr_table[i].sh_name;
        if (strcmp(section_name, ".symtab") == 0) {
            symtab_hdr = &shdr_table[i];
        } else if (strcmp(section_name, ".strtab") == 0) {
            strtab_hdr = &shdr_table[i];
        }
    }

    if (!symtab_hdr || !strtab_hdr) {
        printf("Symbol table or string table not found\n");
        free(shstrtab);
        free(shdr_table);
        fclose(file);
        return;
    }

    // 读取符号表
    int sym_num = symtab_hdr->sh_size / sizeof(Elf64_Sym);
    Elf64_Sym *symtab = malloc(symtab_hdr->sh_size);
    fseek(file, symtab_hdr->sh_offset, SEEK_SET);
    fread(symtab, 1, symtab_hdr->sh_size, file);

    // 读取字符串表
    char *strtab = malloc(strtab_hdr->sh_size);
    fseek(file, strtab_hdr->sh_offset, SEEK_SET);
    fread(strtab, 1, strtab_hdr->sh_size, file);

    // 解析函数符号
    func_count = 0;
    printf("\n=== Function Symbols ===\n");
    printf("%-4s %-20s %-18s %-18s %-10s\n", "Idx", "Name", "Entry", "Exit", "Size");
    printf("--------------------------------------------------------------------------------\n");
    
    for (int i = 0; i < sym_num && func_count < MAX_FUNC_NUM; i++) {
        Elf64_Sym *sym = &symtab[i];
        
        // 只提取函数符号 (STT_FUNC)
        if (ELF64_ST_TYPE(sym->st_info) == STT_FUNC && sym->st_size > 0) {
            char *func_name = strtab + sym->st_name;
            
            // 过滤掉空名字的符号
            if (func_name[0] == '\0') continue;
            
            func_table[func_count].entry_addr = sym->st_value;
            func_table[func_count].size = sym->st_size;
            func_table[func_count].exit_addr = sym->st_value + sym->st_size - 4; // 最后一条指令地址
            strncpy(func_table[func_count].name, func_name, sizeof(func_table[func_count].name) - 1);
            func_table[func_count].name[sizeof(func_table[func_count].name) - 1] = '\0';
            
            printf("%-4d %-20s 0x%016lx 0x%016lx %-10lu\n",
                   func_count,
                   func_table[func_count].name,
                   func_table[func_count].entry_addr,
                   func_table[func_count].exit_addr,
                   func_table[func_count].size);
            
            func_count++;
        }
    }
    
    printf("\nTotal functions found: %d\n", func_count);

    // 清理资源
    free(strtab);
    free(symtab);
    free(shstrtab);
    free(shdr_table);
    fclose(file);
}

// 根据地址查找函数名
const char* get_func_name(uint64_t addr) {
    for (int i = 0; i < func_count; i++) {
        if (addr >= func_table[i].entry_addr && 
            addr < func_table[i].entry_addr + func_table[i].size) {
            return func_table[i].name;
        }
    }
    return NULL;
}

// 检查地址是否是函数入口
int is_func_entry(uint64_t addr) {
    for (int i = 0; i < func_count; i++) {
        if (addr == func_table[i].entry_addr) {
            return 1;
        }
    }
    return 0;
}

// 检查地址是否是函数出口（最后一条指令）
int is_func_exit(uint64_t addr) {
    for (int i = 0; i < func_count; i++) {
        if (addr >= func_table[i].exit_addr && 
            addr < func_table[i].entry_addr + func_table[i].size) {
            return 1;
        }
    }
    return 0;
}

// 获取函数总数
int get_func_count() {
    return func_count;
}

// 获取函数信息
const func_info* get_func_info(int index) {
    if (index >= 0 && index < func_count) {
        return &func_table[index];
    }
    return NULL;
}

void check_ftrace(uint64_t pc, uint32_t inst) {
    // 检查是否是函数入口
    if (is_func_entry(pc)) {
        const char* func_name = get_func_name(pc);
        if (func_name) {
            // printf("Entering function: %s at 0x%lx\n", func_name, pc);
            if (ftrace_stack_idx < MAX_FTRACE_DEPTH) {
                strncpy(ftrace_stack[ftrace_stack_idx].func_name, func_name, sizeof(ftrace_stack[ftrace_stack_idx].func_name) - 1);
                ftrace_stack[ftrace_stack_idx].type = 0; // entry
                ftrace_stack[ftrace_stack_idx].depth = deepest_ftrace_depth++;
                ftrace_stack[ftrace_stack_idx].active = true;
                ftrace_stack[ftrace_stack_idx].pc = pc;
                ftrace_stack_idx++;
            } else {
                // printf("Ftrace stack overflow!\n");
            }
        }
    }
    
    // 检查是否是 ret 指令（jalr x0, 0(ra) 或 jalr zero, ra, 0）
    // RISC-V ret 伪指令编码为: jalr x0, 0(x1)
    // 格式: jalr rd, rs1, imm
    // opcode=0x67, rd=0 (x0/zero), rs1=1 (x1/ra), imm=0
    uint32_t opcode = inst & 0x7F;
    uint32_t rd = (inst >> 7) & 0x1F;
    uint32_t funct3 = (inst >> 12) & 0x7;
    uint32_t rs1 = (inst >> 15) & 0x1F;
    int32_t imm = ((int32_t)inst) >> 20;  // 符号扩展立即数
    
    // jalr 指令：opcode=0x67, funct3=0x0
    // ret 指令：jalr x0, 0(ra) -> rd=0, rs1=1, imm=0
    if (opcode == 0x67 && funct3 == 0x0 && rd == 0 && rs1 == 1 && imm == 0) {
        const char* func_name = get_func_name(pc);
        if (func_name) {
            // printf("Exiting function: %s at 0x%lx\n", func_name, pc);
            if (ftrace_stack_idx < MAX_FTRACE_DEPTH) {
                strncpy(ftrace_stack[ftrace_stack_idx].func_name, func_name, sizeof(ftrace_stack[ftrace_stack_idx].func_name) - 1);
                ftrace_stack[ftrace_stack_idx].type = 1; // exit
                ftrace_stack[ftrace_stack_idx].depth = deepest_ftrace_depth > 0 ? --deepest_ftrace_depth : 0;
                ftrace_stack[ftrace_stack_idx].active = true;
                ftrace_stack[ftrace_stack_idx].pc = pc;
                ftrace_stack_idx++;
                // if(deepest_ftrace_depth > 0) deepest_ftrace_depth--;
            } else {
                // printf("Ftrace stack underflow!\n");
            }
        }
    }
}

void display_ftrace() {
    printf("==== Function Trace ====\n");
    for (int i = 0; i < ftrace_stack_idx; i++) {
        char indent[64] = {0};
        if(ftrace_stack[i].active) {
            for (int j = 0; j < ftrace_stack[i].depth; j++) {
                strcat(indent, "  ");
            }
            if (ftrace_stack[i].type == 0) {
                printf("%s--> %s \t\t\t(0x%lx)\n", indent, ftrace_stack[i].func_name, ftrace_stack[i].pc);
            } else {
                printf("%s<-- %s \t\t\t(0x%lx)\n", indent, ftrace_stack[i].func_name, ftrace_stack[i].pc);
            }
        }
    }
    printf("========================\n");
}