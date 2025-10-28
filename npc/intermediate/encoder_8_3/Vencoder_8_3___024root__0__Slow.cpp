// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design implementation internals
// See Vencoder_8_3.h for the primary calling header

#include "Vencoder_8_3__pch.h"

VL_ATTR_COLD void Vencoder_8_3___024root___eval_static(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_static\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

VL_ATTR_COLD void Vencoder_8_3___024root___eval_initial(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_initial\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

VL_ATTR_COLD void Vencoder_8_3___024root___eval_final(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_final\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vencoder_8_3___024root___dump_triggers__stl(Vencoder_8_3___024root* vlSelf);
#endif  // VL_DEBUG
VL_ATTR_COLD bool Vencoder_8_3___024root___eval_phase__stl(Vencoder_8_3___024root* vlSelf);

VL_ATTR_COLD void Vencoder_8_3___024root___eval_settle(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_settle\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
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
            Vencoder_8_3___024root___dump_triggers__stl(vlSelf);
#endif
            VL_FATAL_MT("vsrc/encoder_8_3.v", 1, "", "Settle region did not converge after 100 tries");
        }
        __VstlIterCount = ((IData)(1U) + __VstlIterCount);
        __VstlContinue = 0U;
        if (Vencoder_8_3___024root___eval_phase__stl(vlSelf)) {
            __VstlContinue = 1U;
        }
        vlSelfRef.__VstlFirstIteration = 0U;
    }
}

VL_ATTR_COLD void Vencoder_8_3___024root___eval_triggers__stl(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_triggers__stl\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    vlSelfRef.__VstlTriggered.setBit(0U, (IData)(vlSelfRef.__VstlFirstIteration));
#ifdef VL_DEBUG
    if (VL_UNLIKELY(vlSymsp->_vm_contextp__->debug())) {
        Vencoder_8_3___024root___dump_triggers__stl(vlSelf);
    }
#endif
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vencoder_8_3___024root___dump_triggers__stl(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___dump_triggers__stl\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
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

void Vencoder_8_3___024root___ico_sequent__TOP__0(Vencoder_8_3___024root* vlSelf);

VL_ATTR_COLD void Vencoder_8_3___024root___eval_stl(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_stl\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1ULL & vlSelfRef.__VstlTriggered.word(0U))) {
        Vencoder_8_3___024root___ico_sequent__TOP__0(vlSelf);
    }
}

VL_ATTR_COLD bool Vencoder_8_3___024root___eval_phase__stl(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___eval_phase__stl\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    CData/*0:0*/ __VstlExecute;
    // Body
    Vencoder_8_3___024root___eval_triggers__stl(vlSelf);
    __VstlExecute = vlSelfRef.__VstlTriggered.any();
    if (__VstlExecute) {
        Vencoder_8_3___024root___eval_stl(vlSelf);
    }
    return (__VstlExecute);
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vencoder_8_3___024root___dump_triggers__ico(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___dump_triggers__ico\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
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
VL_ATTR_COLD void Vencoder_8_3___024root___dump_triggers__act(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___dump_triggers__act\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1U & (~ vlSelfRef.__VactTriggered.any()))) {
        VL_DBG_MSGF("         No triggers active\n");
    }
}
#endif  // VL_DEBUG

#ifdef VL_DEBUG
VL_ATTR_COLD void Vencoder_8_3___024root___dump_triggers__nba(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___dump_triggers__nba\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1U & (~ vlSelfRef.__VnbaTriggered.any()))) {
        VL_DBG_MSGF("         No triggers active\n");
    }
}
#endif  // VL_DEBUG

VL_ATTR_COLD void Vencoder_8_3___024root___ctor_var_reset(Vencoder_8_3___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vencoder_8_3___024root___ctor_var_reset\n"); );
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    const uint64_t __VscopeHash = VL_MURMUR64_HASH(vlSelf->name());
    vlSelf->din = VL_SCOPED_RAND_RESET_I(8, __VscopeHash, 15192908731043726583ull);
    vlSelf->en = VL_SCOPED_RAND_RESET_I(1, __VscopeHash, 7710216835639188562ull);
    vlSelf->dout = VL_SCOPED_RAND_RESET_I(3, __VscopeHash, 11474705599699299244ull);
    vlSelf->valid = VL_SCOPED_RAND_RESET_I(1, __VscopeHash, 4944192500720994163ull);
    vlSelf->seg_out = VL_SCOPED_RAND_RESET_I(8, __VscopeHash, 222093509035185893ull);
    vlSelf->encoder_8_3__DOT__seg_in = VL_SCOPED_RAND_RESET_I(4, __VscopeHash, 15052135371488502140ull);
}
