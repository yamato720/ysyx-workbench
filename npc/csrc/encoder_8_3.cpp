// Simple Verilator testbench for a module with the same base name: xor_switch
// Expects a Verilog top module named 'xor_switch' with ports: a, b (inputs), f (output)

#include <verilated.h>
#include "Vencoder_8_3.h"
#include "verilated_vcd_c.h"


#include <cstdio>
#include <cstdlib>
#include <cassert>
#include <ctime>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <sys/types.h>


 
vluint64_t main_time = 0;  //initial 仿真时间
double sc_time_stamp() {  // Called by $time in Verilog
    return main_time;  // converts to double, to match
}


int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);

  Verilated::traceEverOn(true); //导出vcd波形需要加此语句
 
  VerilatedVcdC* tfp = new VerilatedVcdC(); //导出vcd波形需要加此语句


  Vencoder_8_3* top = new Vencoder_8_3();
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
  std::srand(0xC0FFEE);
  int kIters = 1000;
    int i = 0;
  while(!sc_time_stamp() < 20 && kIters--) {
    int in = std::rand() % 8;
    int en = (i <= 3) ? 0 : 1;

    top->din = in;
    top->en = en;

    // Evaluate combinational logic
    top->eval();

    int d_out = top->dout;
    int valid = top->valid;
    int seg_out = top->seg_out;
    {
        const int width = 8;
        char bin[width + 1];
        for (int j = 0; j < width; ++j) bin[j] = ((seg_out >> (width - 1 - j)) & 1) ? '1' : '0';
        bin[width] = '\0';
        std::printf("iter=%4d | in=%d en=%d -> d_out=%d valid=%d seg_out=%s\n", i++, in, en, d_out, valid, bin);
    }
    std::fflush(stdout);
    tfp->dump(main_time); //dump wave
    main_time++; //推动仿真时间

  }
  tfp->close();

  delete tfp;
  delete top;
  return 0;
}