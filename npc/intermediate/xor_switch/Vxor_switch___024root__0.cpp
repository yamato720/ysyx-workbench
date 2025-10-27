// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design implementation internals
// See Vxor_switch.h for the primary calling header

#include "Vxor_switch__pch.h"

#ifdef VL_DEBUG
VL_ATTR_COLD void Vxor_switch___024root___dump_triggers__ico(Vxor_switch___024root* vlSelf);
#endif  // VL_DEBUG

void Vxor_switch___024root___eval_triggers__ico(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_triggers__ico\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    vlSelfRef.__VicoTriggered.setBit(0U, (IData)(vlSelfRef.__VicoFirstIteration));
#ifdef VL_DEBUG
    if (VL_UNLIKELY(vlSymsp->_vm_contextp__->debug())) {
        Vxor_switch___024root___dump_triggers__ico(vlSelf);
    }
#endif
}

void Vxor_switch___024root___ico_sequent__TOP__0(Vxor_switch___024root* vlSelf);

void Vxor_switch___024root___eval_ico(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_ico\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1ULL & vlSelfRef.__VicoTriggered.word(0U))) {
        Vxor_switch___024root___ico_sequent__TOP__0(vlSelf);
    }
}

void Vxor_switch___024root___ico_sequent__TOP__0(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___ico_sequent__TOP__0\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    vlSelfRef.f = ((IData)(vlSelfRef.a) ^ (IData)(vlSelfRef.b));
}

bool Vxor_switch___024root___eval_phase__ico(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_phase__ico\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    CData/*0:0*/ __VicoExecute;
    // Body
    Vxor_switch___024root___eval_triggers__ico(vlSelf);
    __VicoExecute = vlSelfRef.__VicoTriggered.any();
    if (__VicoExecute) {
        Vxor_switch___024root___eval_ico(vlSelf);
    }
    return (__VicoExecute);
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vxor_switch___024root___dump_triggers__act(Vxor_switch___024root* vlSelf);
#endif  // VL_DEBUG

void Vxor_switch___024root___eval_triggers__act(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_triggers__act\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
#ifdef VL_DEBUG
    if (VL_UNLIKELY(vlSymsp->_vm_contextp__->debug())) {
        Vxor_switch___024root___dump_triggers__act(vlSelf);
    }
#endif
}

void Vxor_switch___024root___eval_act(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_act\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

void Vxor_switch___024root___eval_nba(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_nba\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

bool Vxor_switch___024root___eval_phase__act(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_phase__act\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    VlTriggerVec<0> __VpreTriggered;
    CData/*0:0*/ __VactExecute;
    // Body
    Vxor_switch___024root___eval_triggers__act(vlSelf);
    __VactExecute = vlSelfRef.__VactTriggered.any();
    if (__VactExecute) {
        __VpreTriggered.andNot(vlSelfRef.__VactTriggered, vlSelfRef.__VnbaTriggered);
        vlSelfRef.__VnbaTriggered.thisOr(vlSelfRef.__VactTriggered);
        Vxor_switch___024root___eval_act(vlSelf);
    }
    return (__VactExecute);
}

bool Vxor_switch___024root___eval_phase__nba(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_phase__nba\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    CData/*0:0*/ __VnbaExecute;
    // Body
    __VnbaExecute = vlSelfRef.__VnbaTriggered.any();
    if (__VnbaExecute) {
        Vxor_switch___024root___eval_nba(vlSelf);
        vlSelfRef.__VnbaTriggered.clear();
    }
    return (__VnbaExecute);
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vxor_switch___024root___dump_triggers__nba(Vxor_switch___024root* vlSelf);
#endif  // VL_DEBUG

void Vxor_switch___024root___eval(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
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
            Vxor_switch___024root___dump_triggers__ico(vlSelf);
#endif
            VL_FATAL_MT("vsrc/xor_switch.v", 1, "", "Input combinational region did not converge.");
        }
        __VicoIterCount = ((IData)(1U) + __VicoIterCount);
        __VicoContinue = 0U;
        if (Vxor_switch___024root___eval_phase__ico(vlSelf)) {
            __VicoContinue = 1U;
        }
        vlSelfRef.__VicoFirstIteration = 0U;
    }
    __VnbaIterCount = 0U;
    __VnbaContinue = 1U;
    while (__VnbaContinue) {
        if (VL_UNLIKELY(((0x00000064U < __VnbaIterCount)))) {
#ifdef VL_DEBUG
            Vxor_switch___024root___dump_triggers__nba(vlSelf);
#endif
            VL_FATAL_MT("vsrc/xor_switch.v", 1, "", "NBA region did not converge.");
        }
        __VnbaIterCount = ((IData)(1U) + __VnbaIterCount);
        __VnbaContinue = 0U;
        vlSelfRef.__VactIterCount = 0U;
        vlSelfRef.__VactContinue = 1U;
        while (vlSelfRef.__VactContinue) {
            if (VL_UNLIKELY(((0x00000064U < vlSelfRef.__VactIterCount)))) {
#ifdef VL_DEBUG
                Vxor_switch___024root___dump_triggers__act(vlSelf);
#endif
                VL_FATAL_MT("vsrc/xor_switch.v", 1, "", "Active region did not converge.");
            }
            vlSelfRef.__VactIterCount = ((IData)(1U) 
                                         + vlSelfRef.__VactIterCount);
            vlSelfRef.__VactContinue = 0U;
            if (Vxor_switch___024root___eval_phase__act(vlSelf)) {
                vlSelfRef.__VactContinue = 1U;
            }
        }
        if (Vxor_switch___024root___eval_phase__nba(vlSelf)) {
            __VnbaContinue = 1U;
        }
    }
}

#ifdef VL_DEBUG
void Vxor_switch___024root___eval_debug_assertions(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_debug_assertions\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if (VL_UNLIKELY(((vlSelfRef.a & 0xfeU)))) {
        Verilated::overWidthError("a");
    }
    if (VL_UNLIKELY(((vlSelfRef.b & 0xfeU)))) {
        Verilated::overWidthError("b");
    }
}
#endif  // VL_DEBUG
