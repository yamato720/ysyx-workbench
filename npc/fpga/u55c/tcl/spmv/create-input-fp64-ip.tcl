if {$argc != 5} {
  puts stderr "usage: create-input-fp64-ip.tcl PROJECT PART OUTPUT_XCI SYNTH_JOBS PROFILE"
  exit 2
}
lassign $argv project_dir part output_xci synth_jobs profile_path

proc fail {message} {
  puts stderr $message
  exit 3
}

proc profile_value {path key} {
  if {![file isfile $path]} { fail "SPMV input profile not found: $path" }
  set stream [open $path r]
  set content [read $stream]
  close $stream
  foreach line [split $content "\n"] {
    if {[string first "${key}=" $line] == 0} {
      return [string range $line [expr {[string length $key] + 1}] end]
    }
  }
  fail "SPMV input profile is missing $key"
}

if {$part ne "xcu55c-fsvh2892-2L-e"} { fail "U55C FP64 multiply requires xcu55c-fsvh2892-2L-e, got $part" }
if {![string is integer -strict $synth_jobs] || $synth_jobs < 1} { fail "SYNTH_JOBS must be positive" }
foreach {key expected} {
  SPMV_FP64_MUL_PROVIDER xilinx-floating-point-v7.1
  SPMV_FP64_MUL_LATENCY 12
  SPMV_FP64_MUL_II 1
} {
  set actual [profile_value $profile_path $key]
  if {$actual ne $expected} { fail "$key must be $expected, got $actual" }
}

file mkdir [file dirname $output_xci]
set_param general.maxThreads $synth_jobs
create_project spmv_input_fp64_ip $project_dir -part $part -force
set_property target_language Verilog [current_project]
create_ip -name floating_point -vendor xilinx.com -library ip \
  -module_name SpmvFp64MulXilinxCore -dir [file dirname $output_xci]
set ip [get_ips SpmvFp64MulXilinxCore]
foreach {property value} {
  CONFIG.Operation_Type Multiply
  CONFIG.A_Precision_Type Double
  CONFIG.C_A_Exponent_Width 11
  CONFIG.C_A_Fraction_Width 53
  CONFIG.Result_Precision_Type Double
  CONFIG.C_Result_Exponent_Width 11
  CONFIG.C_Result_Fraction_Width 53
  CONFIG.Flow_Control NonBlocking
  CONFIG.Has_RESULT_TREADY false
  CONFIG.Has_ARESETn false
  CONFIG.C_Latency 12
  CONFIG.C_Rate 1
} {
  if {[lsearch -exact [list_property $ip] $property] < 0} {
    fail "floating_point v7.1 does not expose $property"
  }
  set_property $property $value $ip
}
generate_target all $ip
set generated_xci [file join [file dirname $output_xci] SpmvFp64MulXilinxCore SpmvFp64MulXilinxCore.xci]
if {![file isfile $generated_xci]} { fail "Vivado did not generate $generated_xci" }
file copy -force $generated_xci $output_xci
foreach {property expected} {
  CONFIG.Operation_Type Multiply
  CONFIG.A_Precision_Type Double
  CONFIG.C_A_Exponent_Width 11
  CONFIG.C_A_Fraction_Width 53
  CONFIG.Result_Precision_Type Double
  CONFIG.C_Result_Exponent_Width 11
  CONFIG.C_Result_Fraction_Width 53
  CONFIG.Flow_Control NonBlocking
  CONFIG.Has_RESULT_TREADY false
  CONFIG.Has_ARESETn false
  CONFIG.C_Latency 12
  CONFIG.C_Rate 1
} {
  if {[get_property $property $ip] ne $expected} {
    fail "floating_point property mismatch: $property expected=$expected actual=[get_property $property $ip]"
  }
}
close_project
