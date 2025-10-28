// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Tracing implementation internals
#include "verilated_vcd_c.h"
#include "Vencoder_8_3__Syms.h"


void Vencoder_8_3___024root__trace_chg_0_sub_0(Vencoder_8_3___024root* vlSelf, VerilatedVcd::Buffer* bufp);

void Vencoder_8_3___024root__trace_chg_0(void* voidSelf, VerilatedVcd::Buffer* bufp) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root__trace_chg_0\n"); );
    // Body
    Vencoder_8_3___024root* const __restrict vlSelf VL_ATTR_UNUSED = static_cast<Vencoder_8_3___024root*>(voidSelf);
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    if (VL_UNLIKELY(!vlSymsp->__Vm_activity)) return;
    Vencoder_8_3___024root__trace_chg_0_sub_0((&vlSymsp->TOP), bufp);
}

void Vencoder_8_3___024root__trace_chg_0_sub_0(Vencoder_8_3___024root* vlSelf, VerilatedVcd::Buffer* bufp) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root__trace_chg_0_sub_0\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    uint32_t* const oldp VL_ATTR_UNUSED = bufp->oldp(vlSymsp->__Vm_baseCode + 1);
    bufp->chgCData(oldp+0,(vlSelfRef.din),8);
    bufp->chgBit(oldp+1,(vlSelfRef.en));
    bufp->chgCData(oldp+2,(vlSelfRef.dout),3);
    bufp->chgBit(oldp+3,(vlSelfRef.valid));
    bufp->chgCData(oldp+4,(vlSelfRef.seg_out),8);
}

void Vencoder_8_3___024root__trace_cleanup(void* voidSelf, VerilatedVcd* /*unused*/) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root__trace_cleanup\n"); );
    // Locals
    VlUnpacked<CData/*0:0*/, 1> __Vm_traceActivity;
    for (int __Vi0 = 0; __Vi0 < 1; ++__Vi0) {
        __Vm_traceActivity[__Vi0] = 0;
    }
    // Body
    Vencoder_8_3___024root* const __restrict vlSelf VL_ATTR_UNUSED = static_cast<Vencoder_8_3___024root*>(voidSelf);
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    vlSymsp->__Vm_activity = false;
    __Vm_traceActivity[0U] = 0U;
}
