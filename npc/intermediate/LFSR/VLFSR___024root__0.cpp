// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design implementation internals
// See VLFSR.h for the primary calling header

#include "VLFSR__pch.h"

#ifdef VL_DEBUG
VL_ATTR_COLD void VLFSR___024root___dump_triggers__act(VLFSR___024root* vlSelf);
#endif  // VL_DEBUG

void VLFSR___024root___eval_triggers__act(VLFSR___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VLFSR___024root___eval_triggers__act\n"); );
    VLFSR__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    vlSelfRef.__VactTriggered.setBit(0U, ((IData)(vlSelfRef.clk) 
                                          & (~ (IData)(vlSelfRef.__Vtrigprevexpr___TOP__clk__0))));
    vlSelfRef.__VactTriggered.setBit(1U, ((IData)(vlSelfRef.rst) 
                                          & (~ (IData)(vlSelfRef.__Vtrigprevexpr___TOP__rst__0))));
    vlSelfRef.__Vtrigprevexpr___TOP__clk__0 = vlSelfRef.clk;
    vlSelfRef.__Vtrigprevexpr___TOP__rst__0 = vlSelfRef.rst;
#ifdef VL_DEBUG
    if (VL_UNLIKELY(vlSymsp->_vm_contextp__->debug())) {
        VLFSR___024root___dump_triggers__act(vlSelf);
    }
#endif
}

void VLFSR___024root___eval_act(VLFSR___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VLFSR___024root___eval_act\n"); );
    VLFSR__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

void VLFSR___024root___nba_sequent__TOP__0(VLFSR___024root* vlSelf);

void VLFSR___024root___eval_nba(VLFSR___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VLFSR___024root___eval_nba\n"); );
    VLFSR__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((3ULL & vlSelfRef.__VnbaTriggered.word(0U))) {
        VLFSR___024root___nba_sequent__TOP__0(vlSelf);
    }
}

extern const VlUnpacked<CData/*7:0*/, 512> VLFSR__ConstPool__TABLE_h1921ed23_0;

void VLFSR___024root___nba_sequent__TOP__0(VLFSR___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VLFSR___024root___nba_sequent__TOP__0\n"); );
    VLFSR__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    SData/*8:0*/ __Vtableidx1;
    __Vtableidx1 = 0;
    // Body
    __Vtableidx1 = (((IData)(vlSelfRef.lfsr_out) << 1U) 
                    | (IData)(vlSelfRef.rst));
    vlSelfRef.lfsr_out = VLFSR__ConstPool__TABLE_h1921ed23_0
        [__Vtableidx1];
}

bool VLFSR___024root___eval_phase__act(VLFSR___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VLFSR___024root___eval_phase__act\n"); );
    VLFSR__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    VlTriggerVec<2> __VpreTriggered;
    CData/*0:0*/ __VactExecute;
    // Body
    VLFSR___024root___eval_triggers__act(vlSelf);
    __VactExecute = vlSelfRef.__VactTriggered.any();
    if (__VactExecute) {
        __VpreTriggered.andNot(vlSelfRef.__VactTriggered, vlSelfRef.__VnbaTriggered);
        vlSelfRef.__VnbaTriggered.thisOr(vlSelfRef.__VactTriggered);
        VLFSR___024root___eval_act(vlSelf);
    }
    return (__VactExecute);
}

bool VLFSR___024root___eval_phase__nba(VLFSR___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VLFSR___024root___eval_phase__nba\n"); );
    VLFSR__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    CData/*0:0*/ __VnbaExecute;
    // Body
    __VnbaExecute = vlSelfRef.__VnbaTriggered.any();
    if (__VnbaExecute) {
        VLFSR___024root___eval_nba(vlSelf);
        vlSelfRef.__VnbaTriggered.clear();
    }
    return (__VnbaExecute);
}

#ifdef VL_DEBUG
VL_ATTR_COLD void VLFSR___024root___dump_triggers__nba(VLFSR___024root* vlSelf);
#endif  // VL_DEBUG

void VLFSR___024root___eval(VLFSR___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VLFSR___024root___eval\n"); );
    VLFSR__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    IData/*31:0*/ __VnbaIterCount;
    CData/*0:0*/ __VnbaContinue;
    // Body
    __VnbaIterCount = 0U;
    __VnbaContinue = 1U;
    while (__VnbaContinue) {
        if (VL_UNLIKELY(((0x00000064U < __VnbaIterCount)))) {
#ifdef VL_DEBUG
            VLFSR___024root___dump_triggers__nba(vlSelf);
#endif
            VL_FATAL_MT("vsrc/LFSR.v", 1, "", "NBA region did not converge after 100 tries");
        }
        __VnbaIterCount = ((IData)(1U) + __VnbaIterCount);
        __VnbaContinue = 0U;
        vlSelfRef.__VactIterCount = 0U;
        vlSelfRef.__VactContinue = 1U;
        while (vlSelfRef.__VactContinue) {
            if (VL_UNLIKELY(((0x00000064U < vlSelfRef.__VactIterCount)))) {
#ifdef VL_DEBUG
                VLFSR___024root___dump_triggers__act(vlSelf);
#endif
                VL_FATAL_MT("vsrc/LFSR.v", 1, "", "Active region did not converge after 100 tries");
            }
            vlSelfRef.__VactIterCount = ((IData)(1U) 
                                         + vlSelfRef.__VactIterCount);
            vlSelfRef.__VactContinue = 0U;
            if (VLFSR___024root___eval_phase__act(vlSelf)) {
                vlSelfRef.__VactContinue = 1U;
            }
        }
        if (VLFSR___024root___eval_phase__nba(vlSelf)) {
            __VnbaContinue = 1U;
        }
    }
}

#ifdef VL_DEBUG
void VLFSR___024root___eval_debug_assertions(VLFSR___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VLFSR___024root___eval_debug_assertions\n"); );
    VLFSR__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if (VL_UNLIKELY(((vlSelfRef.clk & 0xfeU)))) {
        Verilated::overWidthError("clk");
    }
    if (VL_UNLIKELY(((vlSelfRef.rst & 0xfeU)))) {
        Verilated::overWidthError("rst");
    }
}
#endif  // VL_DEBUG
