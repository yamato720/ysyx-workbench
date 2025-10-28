// Simple Verilator testbench for a module with the same base name: xor_switch
// Expects a Verilog top module named 'xor_switch' with ports: a, b (inputs), f (output)

#include <verilated.h>
#include "VALU.h"
#include "verilated_vcd_c.h"


#include <cstdio>
#include <cstdlib>
#include <cassert>
#include <ctime>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <sys/types.h>


int to_num(int bits)
{

    if (bits & 1 << 3) // negative number
        return bits - (1 << 4);
    else
        return bits;
    
}

void do_rst(VALU* top)
{
    int a = std::rand() % 8;
    int b = std::rand() % 8;
    int pos = std::rand() % 4;
    switch (pos)
    {
    case 0:
        a *= -1;
        break;
    case 1:
        b *= -1;
        break;
    case 2:
        a *= -1;
        b *= -1;
        break;
    default:
        break;
    }
    a &= 0xF;
    b &= 0xF;
    // printf("Testing ADD with a=%d, b=%d, pos=%d\n", to_num(a), to_num(b), pos);
    top->a = a;
    top->b = b;
    top->alu_control = 0; // ADD operation
    top->rst = 1;
    top->eval();
    printf("Reset ALU\n");
}

void do_add(VALU* top)
{
    top->rst = 0;
    int a = std::rand() % 8;
    int b = std::rand() % 8;
    int pos = std::rand() % 4;
    switch (pos)
    {
    case 0:
        a *= -1;
        break;
    case 1:
        b *= -1;
        break;
    case 2:
        a *= -1;
        b *= -1;
        break;
    default:
        break;
    }
    a &= 0xF;
    b &= 0xF;
    // printf("Testing ADD with a=%d, b=%d, pos=%d\n", to_num(a), to_num(b), pos);
    top->a = a;
    top->b = b;
    top->alu_control = 0; // ADD operation
    top->eval();
    int expected = (top->a + top->b) & 0xF;
    std::printf("ADD: %d + %d = %d (expected %d)\n, zero = %d, cout = %d, overflow = %d\n", 
        to_num(top->a), to_num(top->b), to_num(top->alu_result), to_num(expected), top->zero, top->cout, top->overflow);
    std::fflush(stdout);
    assert(top->alu_result == expected);

}

void do_sub(VALU* top)
{
    top->rst = 0;
    int a = std::rand() % 8;
    int b = std::rand() % 8;
    int pos = std::rand() % 4;
    switch (pos)
    {
    case 0:
        a *= -1;
        break;
    case 1:
        b *= -1;
        break;
    case 2:
        a *= -1;
        b *= -1;
        break;
    default:
        break;
    }
    a &= 0xF;
    b &= 0xF;

    top->a = a;
    top->b = b;
    top->alu_control = 1; // SUB operation
    top->eval();
    int expected = (top->a - top->b) & 0xF;
    std::printf("SUB: %d - %d = %d (expected %d)\n, zero = %d, cout = %d, overflow = %d\n", 
        to_num(top->a), to_num(top->b), to_num(top->alu_result), to_num(expected), top->zero, top->cout, top->overflow);
    std::fflush(stdout);
    assert(top->alu_result == expected);
}

void do_not(VALU* top)
{
        top->rst = 0;
    int a = std::rand() % 8;
    int b = std::rand() % 8;
    int pos = std::rand() % 4;
    switch (pos)
    {
    case 0:
        a *= -1;
        break;
    case 1:
        b *= -1;
        break;
    case 2:
        a *= -1;
        b *= -1;
        break;
    default:
        break;
    }
    a &= 0xF;
    b &= 0xF;

    top->a = a;
    top->b = b;
    top->alu_control = 2; // NOT operation
    top->eval();
    int expected = ~top->a & 0xF;
    std::printf("NOT: ~%b = %b (expected %b)\n, zero = %d, cout = %d, overflow = %d\n", 
        (top->a), (top->alu_result), (expected), top->zero, top->cout, top->overflow);
    std::fflush(stdout);
    assert(top->alu_result == expected);

}
void do_and(VALU* top)
{
    top->rst = 0;
    int a = std::rand() % 8;
    int b = std::rand() % 8;
    int pos = std::rand() % 4;
    switch (pos)
    {
    case 0:
        a *= -1;
        break;
    case 1:
        b *= -1;
        break;
    case 2:
        a *= -1;
        b *= -1;
        break;
    default:
        break;
    }
    a &= 0xF;
    b &= 0xF;

    top->a = a;
    top->b = b;
    top->alu_control = 3; // AND operation
    top->eval();
    int expected = top->a & top->b & 0xF;
    std::printf("AND: %b & %b = %b (expected %b)\n, zero = %d, cout = %d, overflow = %d\n", 
        (top->a), (top->b), (top->alu_result), (expected), top->zero, top->cout, top->overflow);
    std::fflush(stdout);
    assert(top->alu_result == expected);

}
void do_or(VALU* top)
{
    top->rst = 0;
    int a = std::rand() % 8;
    int b = std::rand() % 8;
    int pos = std::rand() % 4;
    switch (pos)
    {
    case 0:
        a *= -1;
        break;
    case 1:
        b *= -1;
        break;
    case 2:
        a *= -1;
        b *= -1;
        break;
    default:
        break;
    }
    a &= 0xF;
    b &= 0xF;

    top->a = a;
    top->b = b;
    top->alu_control = 4; // OR operation
    top->eval();
    int expected = top->a | top->b & 0xF;
    std::printf("OR: %b | %b = %b (expected %b)\n, zero = %d, cout = %d, overflow = %d\n", 
        (top->a), (top->b), (top->alu_result), (expected), top->zero, top->cout, top->overflow);
    std::fflush(stdout);
    assert(top->alu_result == expected);

}

void do_xor(VALU* top)
{
    top->rst = 0;
    int a = std::rand() % 8;
    int b = std::rand() % 8;
    int pos = std::rand() % 4;
    switch (pos)
    {
    case 0:
        a *= -1;
        break;
    case 1:
        b *= -1;
        break;
    case 2:
        a *= -1;
        b *= -1;
        break;
    default:
        break;
    }
    a &= 0xF;
    b &= 0xF;

    top->a = a;
    top->b = b;
    top->alu_control = 5; // XOR operation
    top->eval();
    int expected = top->a ^ top->b & 0xF;
    std::printf("XOR: %b ^ %b = %b (expected %b)\n, zero = %d, cout = %d, overflow = %d\n", 
        (top->a), (top->b), (top->alu_result), (expected), top->zero, top->cout, top->overflow);
    std::fflush(stdout);
    assert(top->alu_result == expected);

}

void do_slt(VALU* top)
{
    top->rst = 0;
    int a = std::rand() % 8;
    int b = std::rand() % 8;
    int pos = std::rand() % 4;
    switch (pos)
    {
    case 0:
        a *= -1;
        break;
    case 1:
        b *= -1;
        break;
    case 2:
        a *= -1;
        b *= -1;
        break;
    default:
        break;
    }
    a &= 0xF;
    b &= 0xF;

    top->a = a;
    top->b = b;
    top->alu_control = 6; // SLT operation
    top->eval();
    int expected = (to_num(top->a) < to_num(top->b)) ? 1 : 0;
    std::printf("SLT: %d < %d = %d (expected %d)\n, zero = %d, cout = %d, overflow = %d\n", 
        to_num(top->a), to_num(top->b), to_num(top->alu_result), expected, top->zero, top->cout, top->overflow);
    std::fflush(stdout);
    assert(top->alu_result == expected);

}

void do_equ(VALU* top)
{
    top->rst = 0;
    int a = std::rand() % 8;
    int b = std::rand() % 8;
    int pos = std::rand() % 4;
    switch (pos)
    {
    case 0:
        a *= -1;
        break;
    case 1:
        b *= -1;
        break;
    case 2:
        a *= -1;
        b *= -1;
        break;
    default:
        break;
    }
    a &= 0xF;
    b &= 0xF;

    top->a = a;
    top->b = b;
    top->alu_control = 7; // EQU operation
    top->eval();
    int expected = (top->a == top->b) ? 1 : 0;
    std::printf("EQU: %d == %d = %d (expected %d)\n, zero = %d, cout = %d, overflow = %d\n", 
        to_num(top->a), to_num(top->b), to_num(top->alu_result), expected, top->zero, top->cout, top->overflow);
    std::fflush(stdout);
    assert(top->alu_result == expected);

}



vluint64_t main_time = 0;  //initial 仿真时间
double sc_time_stamp() {  // Called by $time in Verilog
    return main_time;  // converts to double, to match
}


int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);

  Verilated::traceEverOn(true); //导出vcd波形需要加此语句
 
  VerilatedVcdC* tfp = new VerilatedVcdC(); //导出vcd波形需要加此语句


  VALU* top = new VALU();
  top->trace(tfp, 0);

  // Place VCD file in the same directory as the executable
  const char* exe_path = (argc > 0 && argv[0]) ? argv[0] : ".";
  std::string exe_dir;
  const char* slash = strrchr(exe_path, '/');
  if (slash) exe_dir.assign(exe_path, slash - exe_path);
  else exe_dir = ".";

  // ensure directory exists
  { std::string cmd = std::string("mkdir -p ") + exe_dir;
    system(cmd.c_str()); }

  char vcd_out[512];
  std::time_t t = std::time(NULL);
  struct tm tm;
  localtime_r(&t, &tm);
  snprintf(vcd_out, sizeof(vcd_out), "%s/wave.vcd",
           exe_dir.c_str());

  tfp->open(vcd_out);

  // Run a finite number of randomized trials
  
  int kIters = 1000;
    int i = 0;
  while(!sc_time_stamp() < 20 && kIters--) {
    switch (i % 8) {
      case 0: do_rst(top); break;
      case 1: do_add(top); break;
      case 2: do_sub(top); break;
      case 3: do_not(top); break;
      case 4: do_and(top); break;
      case 5: do_or(top); break;
      case 6: do_xor(top); break;
      case 7: do_slt(top); break;
      case 8: do_equ(top); break;
      default: ; break;
    }
    tfp->dump(main_time); //dump wave
    main_time++; //推动仿真时间
    i++;

  }
  tfp->close();

  delete tfp;
  delete top;
  return 0;
}