// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Model implementation (design independent parts)

#include "Vencoder_8_3__pch.h"
#include "verilated_vcd_c.h"

//============================================================
// Constructors

Vencoder_8_3::Vencoder_8_3(VerilatedContext* _vcontextp__, const char* _vcname__)
    : VerilatedModel{*_vcontextp__}
    , vlSymsp{new Vencoder_8_3__Syms(contextp(), _vcname__, this)}
    , din{vlSymsp->TOP.din}
    , en{vlSymsp->TOP.en}
    , dout{vlSymsp->TOP.dout}
    , valid{vlSymsp->TOP.valid}
    , seg_out{vlSymsp->TOP.seg_out}
    , rootp{&(vlSymsp->TOP)}
{
    // Register model with the context
    contextp()->addModel(this);
    contextp()->traceBaseModelCbAdd(
        [this](VerilatedTraceBaseC* tfp, int levels, int options) { traceBaseModel(tfp, levels, options); });
}

Vencoder_8_3::Vencoder_8_3(const char* _vcname__)
    : Vencoder_8_3(Verilated::threadContextp(), _vcname__)
{
}

//============================================================
// Destructor

Vencoder_8_3::~Vencoder_8_3() {
    delete vlSymsp;
}

//============================================================
// Evaluation function

#ifdef VL_DEBUG
void Vencoder_8_3___024root___eval_debug_assertions(Vencoder_8_3___024root* vlSelf);
#endif  // VL_DEBUG
void Vencoder_8_3___024root___eval_static(Vencoder_8_3___024root* vlSelf);
void Vencoder_8_3___024root___eval_initial(Vencoder_8_3___024root* vlSelf);
void Vencoder_8_3___024root___eval_settle(Vencoder_8_3___024root* vlSelf);
void Vencoder_8_3___024root___eval(Vencoder_8_3___024root* vlSelf);

void Vencoder_8_3::eval_step() {
    VL_DEBUG_IF(VL_DBG_MSGF("+++++TOP Evaluate Vencoder_8_3::eval_step\n"); );
#ifdef VL_DEBUG
    // Debug assertions
    Vencoder_8_3___024root___eval_debug_assertions(&(vlSymsp->TOP));
#endif  // VL_DEBUG
    vlSymsp->__Vm_activity = true;
    vlSymsp->__Vm_deleter.deleteAll();
    if (VL_UNLIKELY(!vlSymsp->__Vm_didInit)) {
        vlSymsp->__Vm_didInit = true;
        VL_DEBUG_IF(VL_DBG_MSGF("+ Initial\n"););
        Vencoder_8_3___024root___eval_static(&(vlSymsp->TOP));
        Vencoder_8_3___024root___eval_initial(&(vlSymsp->TOP));
        Vencoder_8_3___024root___eval_settle(&(vlSymsp->TOP));
    }
    VL_DEBUG_IF(VL_DBG_MSGF("+ Eval\n"););
    Vencoder_8_3___024root___eval(&(vlSymsp->TOP));
    // Evaluate cleanup
    Verilated::endOfEval(vlSymsp->__Vm_evalMsgQp);
}

//============================================================
// Events and timing
bool Vencoder_8_3::eventsPending() { return false; }

uint64_t Vencoder_8_3::nextTimeSlot() {
    VL_FATAL_MT(__FILE__, __LINE__, "", "No delays in the design");
    return 0;
}

//============================================================
// Utilities

const char* Vencoder_8_3::name() const {
    return vlSymsp->name();
}

//============================================================
// Invoke final blocks

void Vencoder_8_3___024root___eval_final(Vencoder_8_3___024root* vlSelf);

VL_ATTR_COLD void Vencoder_8_3::final() {
    Vencoder_8_3___024root___eval_final(&(vlSymsp->TOP));
}

//============================================================
// Implementations of abstract methods from VerilatedModel

const char* Vencoder_8_3::hierName() const { return vlSymsp->name(); }
const char* Vencoder_8_3::modelName() const { return "Vencoder_8_3"; }
unsigned Vencoder_8_3::threads() const { return 1; }
void Vencoder_8_3::prepareClone() const { contextp()->prepareClone(); }
void Vencoder_8_3::atClone() const {
    contextp()->threadPoolpOnClone();
}
std::unique_ptr<VerilatedTraceConfig> Vencoder_8_3::traceConfig() const {
    return std::unique_ptr<VerilatedTraceConfig>{new VerilatedTraceConfig{false, false, false}};
};

//============================================================
// Trace configuration

void Vencoder_8_3___024root__trace_decl_types(VerilatedVcd* tracep);

void Vencoder_8_3___024root__trace_init_top(Vencoder_8_3___024root* vlSelf, VerilatedVcd* tracep);

VL_ATTR_COLD static void trace_init(void* voidSelf, VerilatedVcd* tracep, uint32_t code) {
    // Callback from tracep->open()
    Vencoder_8_3___024root* const __restrict vlSelf VL_ATTR_UNUSED = static_cast<Vencoder_8_3___024root*>(voidSelf);
    Vencoder_8_3__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    if (!vlSymsp->_vm_contextp__->calcUnusedSigs()) {
        VL_FATAL_MT(__FILE__, __LINE__, __FILE__,
            "Turning on wave traces requires Verilated::traceEverOn(true) call before time 0.");
    }
    vlSymsp->__Vm_baseCode = code;
    tracep->pushPrefix(std::string{vlSymsp->name()}, VerilatedTracePrefixType::SCOPE_MODULE);
    Vencoder_8_3___024root__trace_decl_types(tracep);
    Vencoder_8_3___024root__trace_init_top(vlSelf, tracep);
    tracep->popPrefix();
}

VL_ATTR_COLD void Vencoder_8_3___024root__trace_register(Vencoder_8_3___024root* vlSelf, VerilatedVcd* tracep);

VL_ATTR_COLD void Vencoder_8_3::traceBaseModel(VerilatedTraceBaseC* tfp, int levels, int options) {
    (void)levels; (void)options;
    VerilatedVcdC* const stfp = dynamic_cast<VerilatedVcdC*>(tfp);
    if (VL_UNLIKELY(!stfp)) {
        vl_fatal(__FILE__, __LINE__, __FILE__,"'Vencoder_8_3::trace()' called on non-VerilatedVcdC object;"
            " use --trace-fst with VerilatedFst object, and --trace-vcd with VerilatedVcd object");
    }
    stfp->spTrace()->addModel(this);
    stfp->spTrace()->addInitCb(&trace_init, &(vlSymsp->TOP));
    Vencoder_8_3___024root__trace_register(&(vlSymsp->TOP), stfp->spTrace());
}
