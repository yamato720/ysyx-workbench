// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Model implementation (design independent parts)

#include "Vxor_switch__pch.h"
#include "verilated_vcd_c.h"

//============================================================
// Constructors

Vxor_switch::Vxor_switch(VerilatedContext* _vcontextp__, const char* _vcname__)
    : VerilatedModel{*_vcontextp__}
    , vlSymsp{new Vxor_switch__Syms(contextp(), _vcname__, this)}
    , a{vlSymsp->TOP.a}
    , b{vlSymsp->TOP.b}
    , f{vlSymsp->TOP.f}
    , rootp{&(vlSymsp->TOP)}
{
    // Register model with the context
    contextp()->addModel(this);
    contextp()->traceBaseModelCbAdd(
        [this](VerilatedTraceBaseC* tfp, int levels, int options) { traceBaseModel(tfp, levels, options); });
}

Vxor_switch::Vxor_switch(const char* _vcname__)
    : Vxor_switch(Verilated::threadContextp(), _vcname__)
{
}

//============================================================
// Destructor

Vxor_switch::~Vxor_switch() {
    delete vlSymsp;
}

//============================================================
// Evaluation function

#ifdef VL_DEBUG
void Vxor_switch___024root___eval_debug_assertions(Vxor_switch___024root* vlSelf);
#endif  // VL_DEBUG
void Vxor_switch___024root___eval_static(Vxor_switch___024root* vlSelf);
void Vxor_switch___024root___eval_initial(Vxor_switch___024root* vlSelf);
void Vxor_switch___024root___eval_settle(Vxor_switch___024root* vlSelf);
void Vxor_switch___024root___eval(Vxor_switch___024root* vlSelf);

void Vxor_switch::eval_step() {
    VL_DEBUG_IF(VL_DBG_MSGF("+++++TOP Evaluate Vxor_switch::eval_step\n"); );
#ifdef VL_DEBUG
    // Debug assertions
    Vxor_switch___024root___eval_debug_assertions(&(vlSymsp->TOP));
#endif  // VL_DEBUG
    vlSymsp->__Vm_activity = true;
    vlSymsp->__Vm_deleter.deleteAll();
    if (VL_UNLIKELY(!vlSymsp->__Vm_didInit)) {
        vlSymsp->__Vm_didInit = true;
        VL_DEBUG_IF(VL_DBG_MSGF("+ Initial\n"););
        Vxor_switch___024root___eval_static(&(vlSymsp->TOP));
        Vxor_switch___024root___eval_initial(&(vlSymsp->TOP));
        Vxor_switch___024root___eval_settle(&(vlSymsp->TOP));
    }
    VL_DEBUG_IF(VL_DBG_MSGF("+ Eval\n"););
    Vxor_switch___024root___eval(&(vlSymsp->TOP));
    // Evaluate cleanup
    Verilated::endOfEval(vlSymsp->__Vm_evalMsgQp);
}

//============================================================
// Events and timing
bool Vxor_switch::eventsPending() { return false; }

uint64_t Vxor_switch::nextTimeSlot() {
    VL_FATAL_MT(__FILE__, __LINE__, "", "No delays in the design");
    return 0;
}

//============================================================
// Utilities

const char* Vxor_switch::name() const {
    return vlSymsp->name();
}

//============================================================
// Invoke final blocks

void Vxor_switch___024root___eval_final(Vxor_switch___024root* vlSelf);

VL_ATTR_COLD void Vxor_switch::final() {
    Vxor_switch___024root___eval_final(&(vlSymsp->TOP));
}

//============================================================
// Implementations of abstract methods from VerilatedModel

const char* Vxor_switch::hierName() const { return vlSymsp->name(); }
const char* Vxor_switch::modelName() const { return "Vxor_switch"; }
unsigned Vxor_switch::threads() const { return 1; }
void Vxor_switch::prepareClone() const { contextp()->prepareClone(); }
void Vxor_switch::atClone() const {
    contextp()->threadPoolpOnClone();
}
std::unique_ptr<VerilatedTraceConfig> Vxor_switch::traceConfig() const {
    return std::unique_ptr<VerilatedTraceConfig>{new VerilatedTraceConfig{false, false, false}};
};

//============================================================
// Trace configuration

void Vxor_switch___024root__trace_decl_types(VerilatedVcd* tracep);

void Vxor_switch___024root__trace_init_top(Vxor_switch___024root* vlSelf, VerilatedVcd* tracep);

VL_ATTR_COLD static void trace_init(void* voidSelf, VerilatedVcd* tracep, uint32_t code) {
    // Callback from tracep->open()
    Vxor_switch___024root* const __restrict vlSelf VL_ATTR_UNUSED = static_cast<Vxor_switch___024root*>(voidSelf);
    Vxor_switch__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    if (!vlSymsp->_vm_contextp__->calcUnusedSigs()) {
        VL_FATAL_MT(__FILE__, __LINE__, __FILE__,
            "Turning on wave traces requires Verilated::traceEverOn(true) call before time 0.");
    }
    vlSymsp->__Vm_baseCode = code;
    tracep->pushPrefix(std::string{vlSymsp->name()}, VerilatedTracePrefixType::SCOPE_MODULE);
    Vxor_switch___024root__trace_decl_types(tracep);
    Vxor_switch___024root__trace_init_top(vlSelf, tracep);
    tracep->popPrefix();
}

VL_ATTR_COLD void Vxor_switch___024root__trace_register(Vxor_switch___024root* vlSelf, VerilatedVcd* tracep);

VL_ATTR_COLD void Vxor_switch::traceBaseModel(VerilatedTraceBaseC* tfp, int levels, int options) {
    (void)levels; (void)options;
    VerilatedVcdC* const stfp = dynamic_cast<VerilatedVcdC*>(tfp);
    if (VL_UNLIKELY(!stfp)) {
        vl_fatal(__FILE__, __LINE__, __FILE__,"'Vxor_switch::trace()' called on non-VerilatedVcdC object;"
            " use --trace-fst with VerilatedFst object, and --trace-vcd with VerilatedVcd object");
    }
    stfp->spTrace()->addModel(this);
    stfp->spTrace()->addInitCb(&trace_init, &(vlSymsp->TOP));
    Vxor_switch___024root__trace_register(&(vlSymsp->TOP), stfp->spTrace());
}
