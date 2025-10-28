// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design implementation internals
// See Vencoder_8_3.h for the primary calling header

#include "Vencoder_8_3__pch.h"

#ifdef VL_DEBUG
VL_ATTR_COLD void Vencoder_8_3___024root___dump_triggers__ico(Vencoder_8_3___024root* vlSelf);
#endif  // VL_DEBUG

void Vencoder_8_3___024root___eval_triggers__ico(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_triggers__ico\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    vlSelfRef.__VicoTriggered.setBit(0U, (IData)(vlSelfRef.__VicoFirstIteration));
#ifdef VL_DEBUG
    if (VL_UNLIKELY(vlSymsp->_vm_contextp__->debug())) {
        Vencoder_8_3___024root___dump_triggers__ico(vlSelf);
    }
#endif
}

void Vencoder_8_3___024root___ico_sequent__TOP__0(Vencoder_8_3___024root* vlSelf);

void Vencoder_8_3___024root___eval_ico(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_ico\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1ULL & vlSelfRef.__VicoTriggered.word(0U))) {
        Vencoder_8_3___024root___ico_sequent__TOP__0(vlSelf);
    }
}

extern const VlUnpacked<CData/*2:0*/, 512> Vencoder_8_3__ConstPool__TABLE_hae94bb89_0;
extern const VlUnpacked<CData/*0:0*/, 512> Vencoder_8_3__ConstPool__TABLE_hce604057_0;
extern const VlUnpacked<CData/*7:0*/, 8> Vencoder_8_3__ConstPool__TABLE_h9d544c34_0;

void Vencoder_8_3___024root___ico_sequent__TOP__0(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___ico_sequent__TOP__0\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    SData/*8:0*/ __Vtableidx1;
    __Vtableidx1 = 0;
    CData/*2:0*/ __Vtableidx2;
    __Vtableidx2 = 0;
    // Body
    __Vtableidx1 = (((IData)(vlSelfRef.din) << 1U) 
                    | (IData)(vlSelfRef.en));
    vlSelfRef.dout = Vencoder_8_3__ConstPool__TABLE_hae94bb89_0
        [__Vtableidx1];
    vlSelfRef.valid = Vencoder_8_3__ConstPool__TABLE_hce604057_0
        [__Vtableidx1];
    __Vtableidx2 = vlSelfRef.dout;
    vlSelfRef.seg_out = Vencoder_8_3__ConstPool__TABLE_h9d544c34_0
        [__Vtableidx2];
}

bool Vencoder_8_3___024root___eval_phase__ico(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_phase__ico\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    CData/*0:0*/ __VicoExecute;
    // Body
    Vencoder_8_3___024root___eval_triggers__ico(vlSelf);
    __VicoExecute = vlSelfRef.__VicoTriggered.any();
    if (__VicoExecute) {
        Vencoder_8_3___024root___eval_ico(vlSelf);
    }
    return (__VicoExecute);
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vencoder_8_3___024root___dump_triggers__act(Vencoder_8_3___024root* vlSelf);
#endif  // VL_DEBUG

void Vencoder_8_3___024root___eval_triggers__act(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_triggers__act\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
#ifdef VL_DEBUG
    if (VL_UNLIKELY(vlSymsp->_vm_contextp__->debug())) {
        Vencoder_8_3___024root___dump_triggers__act(vlSelf);
    }
#endif
}

void Vencoder_8_3___024root___eval_act(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_act\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

void Vencoder_8_3___024root___eval_nba(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_nba\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

bool Vencoder_8_3___024root___eval_phase__act(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_phase__act\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    VlTriggerVec<0> __VpreTriggered;
    CData/*0:0*/ __VactExecute;
    // Body
    Vencoder_8_3___024root___eval_triggers__act(vlSelf);
    __VactExecute = vlSelfRef.__VactTriggered.any();
    if (__VactExecute) {
        __VpreTriggered.andNot(vlSelfRef.__VactTriggered, vlSelfRef.__VnbaTriggered);
        vlSelfRef.__VnbaTriggered.thisOr(vlSelfRef.__VactTriggered);
        Vencoder_8_3___024root___eval_act(vlSelf);
    }
    return (__VactExecute);
}

bool Vencoder_8_3___024root___eval_phase__nba(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_phase__nba\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    CData/*0:0*/ __VnbaExecute;
    // Body
    __VnbaExecute = vlSelfRef.__VnbaTriggered.any();
    if (__VnbaExecute) {
        Vencoder_8_3___024root___eval_nba(vlSelf);
        vlSelfRef.__VnbaTriggered.clear();
    }
    return (__VnbaExecute);
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vencoder_8_3___024root___dump_triggers__nba(Vencoder_8_3___024root* vlSelf);
#endif  // VL_DEBUG

void Vencoder_8_3___024root___eval(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    IData/*31:0*/ __VicoIterCount;
    CData/*0:0*/ __VicoContinue;
    IData/*31:0*/ __VnbaIterCount;
    CData/*0:0*/ __VnbaContinue;
    // Body
    __VicoIterCount = 0U;
    vlSelfRef.__VicoFirstIteration = 1U;
    __VicoContinue = 1U;
    while (__VicoContinue) {
        if (VL_UNLIKELY(((0x00000064U < __VicoIterCount)))) {
#ifdef VL_DEBUG
            Vencoder_8_3___024root___dump_triggers__ico(vlSelf);
#endif
            VL_FATAL_MT("vsrc/encoder_8_3.v", 1, "", "Input combinational region did not converge after 100 tries");
        }
        __VicoIterCount = ((IData)(1U) + __VicoIterCount);
        __VicoContinue = 0U;
        if (Vencoder_8_3___024root___eval_phase__ico(vlSelf)) {
            __VicoContinue = 1U;
        }
        vlSelfRef.__VicoFirstIteration = 0U;
    }
    __VnbaIterCount = 0U;
    __VnbaContinue = 1U;
    while (__VnbaContinue) {
        if (VL_UNLIKELY(((0x00000064U < __VnbaIterCount)))) {
#ifdef VL_DEBUG
            Vencoder_8_3___024root___dump_triggers__nba(vlSelf);
#endif
            VL_FATAL_MT("vsrc/encoder_8_3.v", 1, "", "NBA region did not converge after 100 tries");
        }
        __VnbaIterCount = ((IData)(1U) + __VnbaIterCount);
        __VnbaContinue = 0U;
        vlSelfRef.__VactIterCount = 0U;
        vlSelfRef.__VactContinue = 1U;
        while (vlSelfRef.__VactContinue) {
            if (VL_UNLIKELY(((0x00000064U < vlSelfRef.__VactIterCount)))) {
#ifdef VL_DEBUG
                Vencoder_8_3___024root___dump_triggers__act(vlSelf);
#endif
                VL_FATAL_MT("vsrc/encoder_8_3.v", 1, "", "Active region did not converge after 100 tries");
            }
            vlSelfRef.__VactIterCount = ((IData)(1U) 
                                         + vlSelfRef.__VactIterCount);
            vlSelfRef.__VactContinue = 0U;
            if (Vencoder_8_3___024root___eval_phase__act(vlSelf)) {
                vlSelfRef.__VactContinue = 1U;
            }
        }
        if (Vencoder_8_3___024root___eval_phase__nba(vlSelf)) {
            __VnbaContinue = 1U;
        }
    }
}

#ifdef VL_DEBUG
void Vencoder_8_3___024root___eval_debug_assertions(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_debug_assertions\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if (VL_UNLIKELY(((vlSelfRef.en & 0xfeU)))) {
        Verilated::overWidthError("en");
    }
}
#endif  // VL_DEBUG
