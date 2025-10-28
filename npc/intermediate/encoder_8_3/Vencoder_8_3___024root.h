// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design internal header
// See Vencoder_8_3.h for the primary calling header

#ifndef VERILATED_VENCODER_8_3___024ROOT_H_
#define VERILATED_VENCODER_8_3___024ROOT_H_  // guard

#include "verilated.h"


class Vencoder_8_3__Syms;

class alignas(VL_CACHE_LINE_BYTES) Vencoder_8_3___024root final : public VerilatedModule {
  public:

    // DESIGN SPECIFIC STATE
    VL_IN8(din,7,0);
    VL_IN8(en,0,0);
    VL_OUT8(dout,2,0);
    VL_OUT8(valid,0,0);
    VL_OUT8(seg_out,7,0);
    CData/*3:0*/ encoder_8_3__DOT__seg_in;
    CData/*0:0*/ __VstlFirstIteration;
    CData/*0:0*/ __VicoFirstIteration;
    CData/*0:0*/ __VactContinue;
    IData/*31:0*/ __VactIterCount;
    VlTriggerVec<1> __VstlTriggered;
    VlTriggerVec<1> __VicoTriggered;
    VlTriggerVec<0> __VactTriggered;
    VlTriggerVec<0> __VnbaTriggered;

    // INTERNAL VARIABLES
    Vencoder_8_3__Syms* const vlSymsp;

    // CONSTRUCTORS
    Vencoder_8_3___024root(Vencoder_8_3__Syms* symsp, const char* v__name);
    ~Vencoder_8_3___024root();
    VL_UNCOPYABLE(Vencoder_8_3___024root);

    // INTERNAL METHODS
    void __Vconfigure(bool first);
};


#endif  // guard
