// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design implementation internals
// See Vxor_switch.h for the primary calling header

#include "Vxor_switch__pch.h"

VL_ATTR_COLD void Vxor_switch___024root___eval_static(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_static\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

VL_ATTR_COLD void Vxor_switch___024root___eval_initial(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_initial\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

VL_ATTR_COLD void Vxor_switch___024root___eval_final(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_final\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vxor_switch___024root___dump_triggers__stl(Vxor_switch___024root* vlSelf);
#endif  // VL_DEBUG
VL_ATTR_COLD bool Vxor_switch___024root___eval_phase__stl(Vxor_switch___024root* vlSelf);

VL_ATTR_COLD void Vxor_switch___024root___eval_settle(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_settle\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    IData/*31:0*/ __VstlIterCount;
    CData/*0:0*/ __VstlContinue;
    // Body
    __VstlIterCount = 0U;
    vlSelfRef.__VstlFirstIteration = 1U;
    __VstlContinue = 1U;
    while (__VstlContinue) {
        if (VL_UNLIKELY(((0x00000064U < __VstlIterCount)))) {
#ifdef VL_DEBUG
            Vxor_switch___024root___dump_triggers__stl(vlSelf);
#endif
            VL_FATAL_MT("vsrc/xor_switch.v", 1, "", "Settle region did not converge after 100 tries");
        }
        __VstlIterCount = ((IData)(1U) + __VstlIterCount);
        __VstlContinue = 0U;
        if (Vxor_switch___024root___eval_phase__stl(vlSelf)) {
            __VstlContinue = 1U;
        }
        vlSelfRef.__VstlFirstIteration = 0U;
    }
}

VL_ATTR_COLD void Vxor_switch___024root___eval_triggers__stl(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_triggers__stl\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    vlSelfRef.__VstlTriggered.setBit(0U, (IData)(vlSelfRef.__VstlFirstIteration));
#ifdef VL_DEBUG
    if (VL_UNLIKELY(vlSymsp->_vm_contextp__->debug())) {
        Vxor_switch___024root___dump_triggers__stl(vlSelf);
    }
#endif
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vxor_switch___024root___dump_triggers__stl(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___dump_triggers__stl\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1U & (~ vlSelfRef.__VstlTriggered.any()))) {
        VL_DBG_MSGF("         No triggers active\n");
    }
    if ((1ULL & vlSelfRef.__VstlTriggered.word(0U))) {
        VL_DBG_MSGF("         'stl' region trigger index 0 is active: Internal 'stl' trigger - first iteration\n");
    }
}
#endif  // VL_DEBUG

void Vxor_switch___024root___ico_sequent__TOP__0(Vxor_switch___024root* vlSelf);

VL_ATTR_COLD void Vxor_switch___024root___eval_stl(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_stl\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1ULL & vlSelfRef.__VstlTriggered.word(0U))) {
        Vxor_switch___024root___ico_sequent__TOP__0(vlSelf);
    }
}

VL_ATTR_COLD bool Vxor_switch___024root___eval_phase__stl(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___eval_phase__stl\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    CData/*0:0*/ __VstlExecute;
    // Body
    Vxor_switch___024root___eval_triggers__stl(vlSelf);
    __VstlExecute = vlSelfRef.__VstlTriggered.any();
    if (__VstlExecute) {
        Vxor_switch___024root___eval_stl(vlSelf);
    }
    return (__VstlExecute);
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vxor_switch___024root___dump_triggers__ico(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___dump_triggers__ico\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1U & (~ vlSelfRef.__VicoTriggered.any()))) {
        VL_DBG_MSGF("         No triggers active\n");
    }
    if ((1ULL & vlSelfRef.__VicoTriggered.word(0U))) {
        VL_DBG_MSGF("         'ico' region trigger index 0 is active: Internal 'ico' trigger - first iteration\n");
    }
}
#endif  // VL_DEBUG

#ifdef VL_DEBUG
VL_ATTR_COLD void Vxor_switch___024root___dump_triggers__act(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___dump_triggers__act\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1U & (~ vlSelfRef.__VactTriggered.any()))) {
        VL_DBG_MSGF("         No triggers active\n");
    }
}
#endif  // VL_DEBUG

#ifdef VL_DEBUG
VL_ATTR_COLD void Vxor_switch___024root___dump_triggers__nba(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___dump_triggers__nba\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1U & (~ vlSelfRef.__VnbaTriggered.any()))) {
        VL_DBG_MSGF("         No triggers active\n");
    }
}
#endif  // VL_DEBUG

VL_ATTR_COLD void Vxor_switch___024root___ctor_var_reset(Vxor_switch___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vxor_switch___024root___ctor_var_reset\n"); );
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    const uint64_t __VscopeHash = VL_MURMUR64_HASH(vlSelf->name());
    vlSelf->a = VL_SCOPED_RAND_RESET_I(1, __VscopeHash, 510903276987443985ull);
    vlSelf->b = VL_SCOPED_RAND_RESET_I(1, __VscopeHash, 16900879642891266615ull);
    vlSelf->f = VL_SCOPED_RAND_RESET_I(1, __VscopeHash, 6217145520856553898ull);
}
