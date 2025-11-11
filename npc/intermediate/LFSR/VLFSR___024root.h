// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design internal header
// See VLFSR.h for the primary calling header

#ifndef VERILATED_VLFSR___024ROOT_H_
#define VERILATED_VLFSR___024ROOT_H_  // guard

#include "verilated.h"


class VLFSR__Syms;

class alignas(VL_CACHE_LINE_BYTES) VLFSR___024root final : public VerilatedModule {
  public:

    // DESIGN SPECIFIC STATE
    VL_IN8(clk,0,0);
    VL_IN8(rst,0,0);
    VL_OUT8(lfsr_out,7,0);
    CData/*0:0*/ __Vtrigprevexpr___TOP__clk__0;
    CData/*0:0*/ __Vtrigprevexpr___TOP__rst__0;
    CData/*0:0*/ __VactContinue;
    IData/*31:0*/ __VactIterCount;
    VlTriggerVec<2> __VactTriggered;
    VlTriggerVec<2> __VnbaTriggered;

    // INTERNAL VARIABLES
    VLFSR__Syms* const vlSymsp;

    // CONSTRUCTORS
    VLFSR___024root(VLFSR__Syms* symsp, const char* v__name);
    ~VLFSR___024root();
    VL_UNCOPYABLE(VLFSR___024root);

    // INTERNAL METHODS
    void __Vconfigure(bool first);
};


#endif  // guard
