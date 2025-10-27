// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design internal header
// See Vxor_switch.h for the primary calling header

#ifndef VERILATED_VXOR_SWITCH___024ROOT_H_
#define VERILATED_VXOR_SWITCH___024ROOT_H_  // guard

#include "verilated.h"


class Vxor_switch__Syms;

class alignas(VL_CACHE_LINE_BYTES) Vxor_switch___024root final : public VerilatedModule {
  public:

    // DESIGN SPECIFIC STATE
    VL_IN8(a,0,0);
    VL_IN8(b,0,0);
    VL_OUT8(f,0,0);
    CData/*0:0*/ __VstlFirstIteration;
    CData/*0:0*/ __VicoFirstIteration;
    CData/*0:0*/ __VactContinue;
    IData/*31:0*/ __VactIterCount;
    VlTriggerVec<1> __VstlTriggered;
    VlTriggerVec<1> __VicoTriggered;
    VlTriggerVec<0> __VactTriggered;
    VlTriggerVec<0> __VnbaTriggered;

    // INTERNAL VARIABLES
    Vxor_switch__Syms* const vlSymsp;

    // CONSTRUCTORS
    Vxor_switch___024root(Vxor_switch__Syms* symsp, const char* v__name);
    ~Vxor_switch___024root();
    VL_UNCOPYABLE(Vxor_switch___024root);

    // INTERNAL METHODS
    void __Vconfigure(bool first);
};


#endif  // guard
