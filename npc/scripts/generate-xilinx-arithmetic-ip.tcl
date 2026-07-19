# Generate the arithmetic IP products used by the NPC stable arithmetic
# adapters. Run from Vivado, for example:
#
#   vivado -mode batch -source npc/scripts/generate-xilinx-arithmetic-ip.tcl \
#     -tclargs -part xczu9eg-ffvb1156-2-i -project-dir build/npc-arithmetic-ip
#
# The generated IP names deliberately do not leak into Chisel. Chisel binds to
# npc_int_multiplier_adapter, npc_int_divider_adapter,
# npc_fp_addsub_adapter, npc_fp_multiplier_adapter, npc_fp_divider_adapter,
# npc_fp_fma_adapter, npc_fp_sqrt_adapter, npc_fp_convert_adapter and
# npc_fp_compare_adapter.
# Those stable wrappers own req/resp tags, ready/valid backpressure and the
# mapping to the vendor ports. Keep wrapper RTL in the Vivado project beside
# the generated .xci files; do not add generated products to git.

proc option_value {name defaultValue} {
  global argv
  set index [lsearch -exact $argv $name]
  if {$index < 0} {
    return $defaultValue
  }
  if {$index + 1 >= [llength $argv]} {
    error "$name requires a value"
  }
  return [lindex $argv [expr {$index + 1}]]
}

set projectDir [file normalize [option_value -project-dir ./generated-ip/vivado]]
set part       [option_value -part xczu9eg-ffvb1156-2-i]
set xlen       [option_value -xlen 32]
set outputDir  [file normalize [option_value -output-dir ./generated-ip]]
set mulLatency [option_value -mul-latency 3]
set mulII      [option_value -mul-ii 1]
set divII      [option_value -div-ii 1]
set faddLatency [option_value -fadd-latency 3]
set faddII      [option_value -fadd-ii 1]
set fmulLatency [option_value -fmul-latency 4]
set fmulII      [option_value -fmul-ii 1]
set fdivLatency [option_value -fdiv-latency 29]
set fdivII      [option_value -fdiv-ii 1]
set ffmaLatency [option_value -ffma-latency 4]
set ffmaII      [option_value -ffma-ii 1]
set fsqrtLatency [option_value -fsqrt-latency 29]
set fsqrtII      [option_value -fsqrt-ii 1]
set fcvtLatency [option_value -fcvt-latency 4]
set fcvtII      [option_value -fcvt-ii 1]
set fcmpLatency [option_value -fcmp-latency 3]
set fcmpII      [option_value -fcmp-ii 1]

if {$xlen != 32 && $xlen != 64} {
  error "-xlen must be 32 or 64"
}
foreach {name value} [list \
  -mul-latency $mulLatency -div-ii $divII \
  -fadd-latency $faddLatency -fadd-ii $faddII \
  -fmul-latency $fmulLatency -fmul-ii $fmulII \
  -fdiv-latency $fdivLatency -fdiv-ii $fdivII \
  -ffma-latency $ffmaLatency -ffma-ii $ffmaII \
  -fsqrt-latency $fsqrtLatency -fsqrt-ii $fsqrtII \
  -fcvt-latency $fcvtLatency -fcvt-ii $fcvtII \
  -fcmp-latency $fcmpLatency -fcmp-ii $fcmpII] {
  if {![string is integer -strict $value] || $value < 1} {
    error "$name must be a positive integer"
  }
}
if {$mulII != 1} {
  error "Multiplier Generator is configured for II=1; -mul-ii must be 1"
}

file mkdir $projectDir
file mkdir $outputDir
create_project npc_arithmetic_ip $projectDir -part $part -force
set_property target_language Verilog [current_project]

# Vivado renamed a handful of CONFIG properties across IP releases. Every
# requested property is reported. A missing property stops the run, because a
# silently-defaulted arithmetic core would not match the timing contract used
# by the Chisel configuration.
proc set_required_config {ip property value} {
  if {[catch {set_property $property $value [get_ips $ip]} message]} {
    error "IP $ip does not support $property=$value: $message"
  }
  set actual [get_property $property [get_ips $ip]]
  if {$actual ne $value} {
    error "IP $ip rejected $property=$value (actual value: $actual)"
  }
}

proc make_ip {name vendor library ipName} {
  create_ip -name $name -vendor $vendor -library $library -module_name $ipName -dir [get_property DIRECTORY [current_project]]
}

# Integer multiplier adapter prepares signed/unsigned operands and preserves
# all RV32M/RV64M operation metadata. Width is XLEN+1 so one signed core can
# represent MULH, MULHSU and MULHU without changing the IP interface.
set multiplierWidth [expr {$xlen + 1}]
make_ip mult_gen xilinx.com ip npc_int_multiplier_ip
set_required_config npc_int_multiplier_ip CONFIG.PortAWidth $multiplierWidth
set_required_config npc_int_multiplier_ip CONFIG.PortBWidth $multiplierWidth
set_required_config npc_int_multiplier_ip CONFIG.Use_Custom_Output_Width true
set_required_config npc_int_multiplier_ip CONFIG.OutputWidthLow 0
set_required_config npc_int_multiplier_ip CONFIG.OutputWidthHigh [expr {2 * $multiplierWidth - 1}]
set_required_config npc_int_multiplier_ip CONFIG.PortAType Signed
set_required_config npc_int_multiplier_ip CONFIG.PortBType Signed
set_required_config npc_int_multiplier_ip CONFIG.Multiplier_Construction Use_Mults
set_required_config npc_int_multiplier_ip CONFIG.PipeStages $mulLatency

# The divider adapter performs RISC-V signed-magnitude conversion and the
# architectural divide-by-zero/overflow rules. The generated core therefore
# remains an unsigned quotient/remainder engine.
make_ip div_gen xilinx.com ip npc_int_divider_ip
set_required_config npc_int_divider_ip CONFIG.dividend_and_quotient_width $xlen
set_required_config npc_int_divider_ip CONFIG.divisor_width $xlen
set_required_config npc_int_divider_ip CONFIG.operand_sign Unsigned
set_required_config npc_int_divider_ip CONFIG.clocks_per_division $divII
set_required_config npc_int_divider_ip CONFIG.FlowControl Blocking
set_required_config npc_int_divider_ip CONFIG.OutTready true
set_required_config npc_int_divider_ip CONFIG.ARESETN true

proc make_float_ip {name operation latency initiationInterval exceptions {aPrecision Single} {resultPrecision Single}} {
  make_ip floating_point xilinx.com ip $name
  set_required_config $name CONFIG.Operation_Type $operation
  set_required_config $name CONFIG.A_Precision_Type $aPrecision
  set_required_config $name CONFIG.Result_Precision_Type $resultPrecision
  set_required_config $name CONFIG.Maximum_Latency false
  set_required_config $name CONFIG.C_Latency $latency
  set_required_config $name CONFIG.C_Rate $initiationInterval
  set_required_config $name CONFIG.Flow_Control Blocking
  set_required_config $name CONFIG.Has_RESULT_TREADY true
  set_required_config $name CONFIG.Has_ARESETn true
  if {[lsearch -exact $exceptions invalid] >= 0} {
    set_required_config $name CONFIG.C_Has_INVALID_OP true
  }
  if {[lsearch -exact $exceptions overflow] >= 0} {
    set_required_config $name CONFIG.C_Has_OVERFLOW true
  }
  if {[lsearch -exact $exceptions underflow] >= 0} {
    set_required_config $name CONFIG.C_Has_UNDERFLOW true
  }
  if {[lsearch -exact $exceptions divide-by-zero] >= 0} {
    set_required_config $name CONFIG.C_Has_DIVIDE_BY_ZERO true
  }
}

make_float_ip npc_fp_addsub_ip Add_Subtract $faddLatency $faddII {invalid overflow underflow}
set_required_config npc_fp_addsub_ip CONFIG.Add_Sub_Value Both
make_float_ip npc_fp_multiplier_ip Multiply $fmulLatency $fmulII {invalid overflow underflow}
make_float_ip npc_fp_divider_ip Divide $fdivLatency $fdivII {invalid overflow underflow divide-by-zero}
make_float_ip npc_fp_fma_ip FMA $ffmaLatency $ffmaII {invalid overflow underflow}
make_float_ip npc_fp_sqrt_ip Square_root $fsqrtLatency $fsqrtII {invalid}
make_float_ip npc_fp_compare_ip Compare $fcmpLatency $fcmpII {invalid}
set_required_config npc_fp_compare_ip CONFIG.C_Compare_Operation Programmable

# These converter cores exercise the stable adapter ABI for both XLEN input
# sizes and signed/unsigned integer-to-float conversion. FPO exposes signed
# float-to-fixed outputs only. The build configuration rejects F with the IP
# backend, because FPO also lacks the required dynamic rounding and NX status;
# do not treat these cores as a complete RV32F/RV64F implementation.
make_float_ip npc_fp_float_to_i32_ip Float_to_fixed $fcvtLatency $fcvtII {invalid} Single Int32
make_float_ip npc_fp_float_to_i64_ip Float_to_fixed $fcvtLatency $fcvtII {invalid} Single Custom
set_required_config npc_fp_float_to_i64_ip CONFIG.C_Result_Exponent_Width 1
set_required_config npc_fp_float_to_i64_ip CONFIG.C_Result_Fraction_Width 63
foreach {name inputPrecision} {
  npc_fp_i32_to_float_ip Int32
  npc_fp_ui32_to_float_ip Uint32
  npc_fp_i64_to_float_ip Int64
  npc_fp_ui64_to_float_ip Uint64
} {
  make_float_ip $name Fixed_to_float $fcvtLatency $fcvtII {} $inputPrecision Single
}

set adapterRtl [file normalize [file join [file dirname [info script]] xilinx-arithmetic-adapters.sv]]
if {![file exists $adapterRtl]} {
  error "missing stable adapter RTL: $adapterRtl"
}
add_files -norecurse $adapterRtl

generate_target all [get_ips]
export_ip_user_files -of_objects [get_ips] -no_script -sync -force -quiet

# FPO may normalize a legal requested latency after it knows the complete
# operand/result format. Record what was generated instead of publishing the
# command-line request as a timing contract. Conversion combines several FPO
# cores, so its public timing is the conservative maximum.
proc generated_positive_config {ip property} {
  set value [get_property CONFIG.$property [get_ips $ip]]
  if {![string is integer -strict $value] || $value < 1} {
    error "IP $ip has no positive generated CONFIG.$property (got: $value)"
  }
  return $value
}

proc maximum_value {values} {
  set maximum [lindex $values 0]
  foreach value $values {
    if {$value > $maximum} {
      set maximum $value
    }
  }
  return $maximum
}

set actualFaddLatency [generated_positive_config npc_fp_addsub_ip C_Latency]
set actualFaddII [generated_positive_config npc_fp_addsub_ip C_Rate]
set actualFmulLatency [generated_positive_config npc_fp_multiplier_ip C_Latency]
set actualFmulII [generated_positive_config npc_fp_multiplier_ip C_Rate]
set actualFdivLatency [generated_positive_config npc_fp_divider_ip C_Latency]
set actualFdivII [generated_positive_config npc_fp_divider_ip C_Rate]
set actualFfmaLatency [generated_positive_config npc_fp_fma_ip C_Latency]
set actualFfmaII [generated_positive_config npc_fp_fma_ip C_Rate]
set actualFsqrtLatency [generated_positive_config npc_fp_sqrt_ip C_Latency]
set actualFsqrtII [generated_positive_config npc_fp_sqrt_ip C_Rate]
set actualFcmpLatency [generated_positive_config npc_fp_compare_ip C_Latency]
set actualFcmpII [generated_positive_config npc_fp_compare_ip C_Rate]
set actualFcvtLatency [maximum_value [list \
  [generated_positive_config npc_fp_float_to_i32_ip C_Latency] \
  [generated_positive_config npc_fp_float_to_i64_ip C_Latency] \
  [generated_positive_config npc_fp_i32_to_float_ip C_Latency] \
  [generated_positive_config npc_fp_ui32_to_float_ip C_Latency] \
  [generated_positive_config npc_fp_i64_to_float_ip C_Latency] \
  [generated_positive_config npc_fp_ui64_to_float_ip C_Latency]]]
set actualFcvtII [maximum_value [list \
  [generated_positive_config npc_fp_float_to_i32_ip C_Rate] \
  [generated_positive_config npc_fp_float_to_i64_ip C_Rate] \
  [generated_positive_config npc_fp_i32_to_float_ip C_Rate] \
  [generated_positive_config npc_fp_ui32_to_float_ip C_Rate] \
  [generated_positive_config npc_fp_i64_to_float_ip C_Rate] \
  [generated_positive_config npc_fp_ui64_to_float_ip C_Rate]]]

set manifest [open [file join $outputDir arithmetic-ip-manifest.tcl] w]
puts $manifest "set NPC_ARITH_XLEN $xlen"
puts $manifest "set NPC_MUL_CYCLES $mulLatency"
puts $manifest "set NPC_MUL_II $mulII"
puts $manifest "set NPC_DIV_CYCLES [get_property CONFIG.latency [get_ips npc_int_divider_ip]]"
puts $manifest "set NPC_DIV_II $divII"
puts $manifest "set NPC_FADD_CYCLES $actualFaddLatency"
puts $manifest "set NPC_FADD_II $actualFaddII"
puts $manifest "set NPC_FMUL_CYCLES $actualFmulLatency"
puts $manifest "set NPC_FMUL_II $actualFmulII"
puts $manifest "set NPC_FDIV_CYCLES $actualFdivLatency"
puts $manifest "set NPC_FDIV_II $actualFdivII"
puts $manifest "set NPC_FFMA_CYCLES $actualFfmaLatency"
puts $manifest "set NPC_FFMA_II $actualFfmaII"
puts $manifest "set NPC_FSQRT_CYCLES $actualFsqrtLatency"
puts $manifest "set NPC_FSQRT_II $actualFsqrtII"
puts $manifest "set NPC_FCVT_CYCLES $actualFcvtLatency"
puts $manifest "set NPC_FCVT_II $actualFcvtII"
puts $manifest "set NPC_FCMP_CYCLES $actualFcmpLatency"
puts $manifest "set NPC_FCMP_II $actualFcmpII"
puts $manifest "set NPC_INT_MULTIPLIER_IP npc_int_multiplier_ip"
puts $manifest "set NPC_INT_DIVIDER_IP npc_int_divider_ip"
puts $manifest "set NPC_FP_ADDSUB_IP npc_fp_addsub_ip"
puts $manifest "set NPC_FP_MULTIPLIER_IP npc_fp_multiplier_ip"
puts $manifest "set NPC_FP_DIVIDER_IP npc_fp_divider_ip"
puts $manifest "set NPC_FP_FMA_IP npc_fp_fma_ip"
puts $manifest "set NPC_FP_SQRT_IP npc_fp_sqrt_ip"
puts $manifest "set NPC_FP_COMPARE_IP npc_fp_compare_ip"
puts $manifest "set NPC_FP_FLOAT_TO_I32_IP npc_fp_float_to_i32_ip"
puts $manifest "set NPC_FP_FLOAT_TO_I64_IP npc_fp_float_to_i64_ip"
puts $manifest "set NPC_FP_I32_TO_FLOAT_IP npc_fp_i32_to_float_ip"
puts $manifest "set NPC_FP_UI32_TO_FLOAT_IP npc_fp_ui32_to_float_ip"
puts $manifest "set NPC_FP_I64_TO_FLOAT_IP npc_fp_i64_to_float_ip"
puts $manifest "set NPC_FP_UI64_TO_FLOAT_IP npc_fp_ui64_to_float_ip"
close $manifest

set environmentManifest [open [file join $outputDir arithmetic-ip-manifest.env] w]
puts $environmentManifest "NPC_XLEN=$xlen"
puts $environmentManifest "NPC_MUL_CYCLES=$mulLatency"
puts $environmentManifest "NPC_MUL_II=$mulII"
puts $environmentManifest "NPC_DIV_CYCLES=[get_property CONFIG.latency [get_ips npc_int_divider_ip]]"
puts $environmentManifest "NPC_DIV_II=$divII"
puts $environmentManifest "NPC_FADD_CYCLES=$actualFaddLatency"
puts $environmentManifest "NPC_FADD_II=$actualFaddII"
puts $environmentManifest "NPC_FMUL_CYCLES=$actualFmulLatency"
puts $environmentManifest "NPC_FMUL_II=$actualFmulII"
puts $environmentManifest "NPC_FDIV_CYCLES=$actualFdivLatency"
puts $environmentManifest "NPC_FDIV_II=$actualFdivII"
puts $environmentManifest "NPC_FFMA_CYCLES=$actualFfmaLatency"
puts $environmentManifest "NPC_FFMA_II=$actualFfmaII"
puts $environmentManifest "NPC_FSQRT_CYCLES=$actualFsqrtLatency"
puts $environmentManifest "NPC_FSQRT_II=$actualFsqrtII"
puts $environmentManifest "NPC_FCVT_CYCLES=$actualFcvtLatency"
puts $environmentManifest "NPC_FCVT_II=$actualFcvtII"
puts $environmentManifest "NPC_FCMP_CYCLES=$actualFcmpLatency"
puts $environmentManifest "NPC_FCMP_II=$actualFcmpII"
close $environmentManifest

puts "Generated NPC arithmetic IP products in $projectDir"
puts "Added stable npc_*_adapter RTL and generated IP output products to the Vivado fileset."
puts "Use $outputDir/arithmetic-ip-manifest.env when elaborating the matching BlackBox configuration."
