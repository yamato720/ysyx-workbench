// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design implementation internals
// See VALU.h for the primary calling header

#include "VALU__pch.h"

#ifdef VL_DEBUG
VL_ATTR_COLD void VALU___024root___dump_triggers__ico(VALU___024root* vlSelf);
#endif  // VL_DEBUG

void VALU___024root___eval_triggers__ico(VALU___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VALU___024root___eval_triggers__ico\n"); );
    VALU__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    vlSelfRef.__VicoTriggered.setBit(0U, (IData)(vlSelfRef.__VicoFirstIteration));
#ifdef VL_DEBUG
    if (VL_UNLIKELY(vlSymsp->_vm_contextp__->debug())) {
        VALU___024root___dump_triggers__ico(vlSelf);
    }
#endif
}

void VALU___024root___ico_sequent__TOP__0(VALU___024root* vlSelf);

void VALU___024root___eval_ico(VALU___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VALU___024root___eval_ico\n"); );
    VALU__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1ULL & vlSelfRef.__VicoTriggered.word(0U))) {
        VALU___024root___ico_sequent__TOP__0(vlSelf);
    }
}

void VALU___024root___ico_sequent__TOP__0(VALU___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VALU___024root___ico_sequent__TOP__0\n"); );
    VALU__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if (vlSelfRef.rst) {
        vlSelfRef.ALU__DOT__result_temp = 0U;
        vlSelfRef.alu_result = 0U;
        vlSelfRef.zero = 1U;
        vlSelfRef.cout = 0U;
        vlSelfRef.overflow = 0U;
    } else if ((4U & (IData)(vlSelfRef.alu_control))) {
        if ((2U & (IData)(vlSelfRef.alu_control))) {
            if ((1U & (IData)(vlSelfRef.alu_control))) {
                vlSelfRef.ALU__DOT__result_temp = (0x0000001fU 
                                                   & ((IData)(1U) 
                                                      + 
                                                      ((IData)(vlSelfRef.a) 
                                                       + 
                                                       (0x0000000fU 
                                                        & (~ (IData)(vlSelfRef.b))))));
                vlSelfRef.zero = (0U == (IData)(vlSelfRef.ALU__DOT__result_temp));
                vlSelfRef.overflow = 0U;
                vlSelfRef.cout = (1U & ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                        >> 4U));
                vlSelfRef.alu_result = (1U & (~ (IData)(vlSelfRef.zero)));
            } else {
                vlSelfRef.ALU__DOT__result_temp = (0x0000001fU 
                                                   & ((IData)(1U) 
                                                      + 
                                                      ((IData)(vlSelfRef.a) 
                                                       + 
                                                       (0x0000000fU 
                                                        & (~ (IData)(vlSelfRef.b))))));
                vlSelfRef.zero = (0U == (IData)(vlSelfRef.ALU__DOT__result_temp));
                vlSelfRef.cout = (1U & ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                        >> 4U));
                vlSelfRef.overflow = (((1U & ((IData)(vlSelfRef.a) 
                                              >> 3U)) 
                                       != (1U & ((IData)(vlSelfRef.b) 
                                                 >> 3U))) 
                                      & ((1U & ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                                >> 3U)) 
                                         != (1U & ((IData)(vlSelfRef.a) 
                                                   >> 3U))));
                vlSelfRef.alu_result = ((1U & ((((1U 
                                                  & ((IData)(vlSelfRef.a) 
                                                     >> 3U)) 
                                                 != 
                                                 (1U 
                                                  & ((IData)(vlSelfRef.b) 
                                                     >> 3U))) 
                                                & ((1U 
                                                    & ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                                       >> 3U)) 
                                                   != 
                                                   (1U 
                                                    & ((IData)(vlSelfRef.a) 
                                                       >> 3U))))
                                                ? (~ 
                                                   ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                                    >> 3U))
                                                : ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                                   >> 3U)))
                                         ? 1U : 0U);
            }
        } else if ((1U & (IData)(vlSelfRef.alu_control))) {
            vlSelfRef.ALU__DOT__result_temp = ((IData)(vlSelfRef.a) 
                                               ^ (IData)(vlSelfRef.b));
            vlSelfRef.zero = (0U == (IData)(vlSelfRef.ALU__DOT__result_temp));
            vlSelfRef.overflow = 0U;
            vlSelfRef.cout = (1U & ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                    >> 4U));
            vlSelfRef.alu_result = (0x0000000fU & (IData)(vlSelfRef.ALU__DOT__result_temp));
        } else {
            vlSelfRef.ALU__DOT__result_temp = ((IData)(vlSelfRef.a) 
                                               | (IData)(vlSelfRef.b));
            vlSelfRef.zero = (0U == (IData)(vlSelfRef.ALU__DOT__result_temp));
            vlSelfRef.overflow = 0U;
            vlSelfRef.cout = (1U & ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                    >> 4U));
            vlSelfRef.alu_result = (0x0000000fU & (IData)(vlSelfRef.ALU__DOT__result_temp));
        }
    } else if ((2U & (IData)(vlSelfRef.alu_control))) {
        if ((1U & (IData)(vlSelfRef.alu_control))) {
            vlSelfRef.ALU__DOT__result_temp = ((IData)(vlSelfRef.a) 
                                               & (IData)(vlSelfRef.b));
            vlSelfRef.zero = (0U == (IData)(vlSelfRef.ALU__DOT__result_temp));
            vlSelfRef.overflow = 0U;
            vlSelfRef.cout = (1U & ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                    >> 4U));
            vlSelfRef.alu_result = (0x0000000fU & (IData)(vlSelfRef.ALU__DOT__result_temp));
        } else {
            vlSelfRef.ALU__DOT__result_temp = (0x0000000fU 
                                               & (~ (IData)(vlSelfRef.a)));
            vlSelfRef.zero = (0U == (IData)(vlSelfRef.ALU__DOT__result_temp));
            vlSelfRef.overflow = 0U;
            vlSelfRef.cout = (1U & ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                    >> 4U));
            vlSelfRef.alu_result = (0x0000000fU & (IData)(vlSelfRef.ALU__DOT__result_temp));
        }
    } else if ((1U & (IData)(vlSelfRef.alu_control))) {
        vlSelfRef.ALU__DOT__result_temp = (0x0000001fU 
                                           & ((IData)(1U) 
                                              + ((IData)(vlSelfRef.a) 
                                                 + 
                                                 (0x0000000fU 
                                                  & (~ (IData)(vlSelfRef.b))))));
        vlSelfRef.zero = (0U == (IData)(vlSelfRef.ALU__DOT__result_temp));
        vlSelfRef.cout = (1U & ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                >> 4U));
        vlSelfRef.overflow = (((1U & ((IData)(vlSelfRef.a) 
                                      >> 3U)) != (1U 
                                                  & ((IData)(vlSelfRef.b) 
                                                     >> 3U))) 
                              & ((1U & ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                        >> 3U)) != 
                                 (1U & ((IData)(vlSelfRef.a) 
                                        >> 3U))));
        vlSelfRef.alu_result = (0x0000000fU & (IData)(vlSelfRef.ALU__DOT__result_temp));
    } else {
        vlSelfRef.ALU__DOT__result_temp = (0x0000001fU 
                                           & ((IData)(vlSelfRef.a) 
                                              + (IData)(vlSelfRef.b)));
        vlSelfRef.zero = (0U == (IData)(vlSelfRef.ALU__DOT__result_temp));
        vlSelfRef.cout = (1U & ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                >> 4U));
        vlSelfRef.overflow = (((1U & ((IData)(vlSelfRef.a) 
                                      >> 3U)) == (1U 
                                                  & ((IData)(vlSelfRef.b) 
                                                     >> 3U))) 
                              & ((1U & ((IData)(vlSelfRef.ALU__DOT__result_temp) 
                                        >> 3U)) != 
                                 (1U & ((IData)(vlSelfRef.a) 
                                        >> 3U))));
        vlSelfRef.alu_result = (0x0000000fU & (IData)(vlSelfRef.ALU__DOT__result_temp));
    }
}

bool VALU___024root___eval_phase__ico(VALU___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VALU___024root___eval_phase__ico\n"); );
    VALU__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    CData/*0:0*/ __VicoExecute;
    // Body
    VALU___024root___eval_triggers__ico(vlSelf);
    __VicoExecute = vlSelfRef.__VicoTriggered.any();
    if (__VicoExecute) {
        VALU___024root___eval_ico(vlSelf);
    }
    return (__VicoExecute);
}

#ifdef VL_DEBUG
VL_ATTR_COLD void VALU___024root___dump_triggers__act(VALU___024root* vlSelf);
#endif  // VL_DEBUG

void VALU___024root___eval_triggers__act(VALU___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VALU___024root___eval_triggers__act\n"); );
    VALU__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
#ifdef VL_DEBUG
    if (VL_UNLIKELY(vlSymsp->_vm_contextp__->debug())) {
        VALU___024root___dump_triggers__act(vlSelf);
    }
#endif
}

void VALU___024root___eval_act(VALU___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VALU___024root___eval_act\n"); );
    VALU__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

void VALU___024root___eval_nba(VALU___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VALU___024root___eval_nba\n"); );
    VALU__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

bool VALU___024root___eval_phase__act(VALU___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VALU___024root___eval_phase__act\n"); );
    VALU__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    VlTriggerVec<0> __VpreTriggered;
    CData/*0:0*/ __VactExecute;
    // Body
    VALU___024root___eval_triggers__act(vlSelf);
    __VactExecute = vlSelfRef.__VactTriggered.any();
    if (__VactExecute) {
        __VpreTriggered.andNot(vlSelfRef.__VactTriggered, vlSelfRef.__VnbaTriggered);
        vlSelfRef.__VnbaTriggered.thisOr(vlSelfRef.__VactTriggered);
        VALU___024root___eval_act(vlSelf);
    }
    return (__VactExecute);
}

bool VALU___024root___eval_phase__nba(VALU___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VALU___024root___eval_phase__nba\n"); );
    VALU__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Locals
    CData/*0:0*/ __VnbaExecute;
    // Body
    __VnbaExecute = vlSelfRef.__VnbaTriggered.any();
    if (__VnbaExecute) {
        VALU___024root___eval_nba(vlSelf);
        vlSelfRef.__VnbaTriggered.clear();
    }
    return (__VnbaExecute);
}

#ifdef VL_DEBUG
VL_ATTR_COLD void VALU___024root___dump_triggers__nba(VALU___024root* vlSelf);
#endif  // VL_DEBUG

void VALU___024root___eval(VALU___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VALU___024root___eval\n"); );
    VALU__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
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
            VALU___024root___dump_triggers__ico(vlSelf);
#endif
            VL_FATAL_MT("vsrc/ALU.v", 1, "", "Input combinational region did not converge after 100 tries");
        }
        __VicoIterCount = ((IData)(1U) + __VicoIterCount);
        __VicoContinue = 0U;
        if (VALU___024root___eval_phase__ico(vlSelf)) {
            __VicoContinue = 1U;
        }
        vlSelfRef.__VicoFirstIteration = 0U;
    }
    __VnbaIterCount = 0U;
    __VnbaContinue = 1U;
    while (__VnbaContinue) {
        if (VL_UNLIKELY(((0x00000064U < __VnbaIterCount)))) {
#ifdef VL_DEBUG
            VALU___024root___dump_triggers__nba(vlSelf);
#endif
            VL_FATAL_MT("vsrc/ALU.v", 1, "", "NBA region did not converge after 100 tries");
        }
        __VnbaIterCount = ((IData)(1U) + __VnbaIterCount);
        __VnbaContinue = 0U;
        vlSelfRef.__VactIterCount = 0U;
        vlSelfRef.__VactContinue = 1U;
        while (vlSelfRef.__VactContinue) {
            if (VL_UNLIKELY(((0x00000064U < vlSelfRef.__VactIterCount)))) {
#ifdef VL_DEBUG
                VALU___024root___dump_triggers__act(vlSelf);
#endif
                VL_FATAL_MT("vsrc/ALU.v", 1, "", "Active region did not converge after 100 tries");
            }
            vlSelfRef.__VactIterCount = ((IData)(1U) 
                                         + vlSelfRef.__VactIterCount);
            vlSelfRef.__VactContinue = 0U;
            if (VALU___024root___eval_phase__act(vlSelf)) {
                vlSelfRef.__VactContinue = 1U;
            }
        }
        if (VALU___024root___eval_phase__nba(vlSelf)) {
            __VnbaContinue = 1U;
        }
    }
}

#ifdef VL_DEBUG
void VALU___024root___eval_debug_assertions(VALU___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    VALU___024root___eval_debug_assertions\n"); );
    VALU__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if (VL_UNLIKELY(((vlSelfRef.rst & 0xfeU)))) {
        Verilated::overWidthError("rst");
    }
    if (VL_UNLIKELY(((vlSelfRef.a & 0xf0U)))) {
        Verilated::overWidthError("a");
    }
    if (VL_UNLIKELY(((vlSelfRef.b & 0xf0U)))) {
        Verilated::overWidthError("b");
    }
    if (VL_UNLIKELY(((vlSelfRef.alu_control & 0xf8U)))) {
        Verilated::overWidthError("alu_control");
    }
}
#endif  // VL_DEBUG
