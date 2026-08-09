# Vivado 2022.2、xcu55c-fsvh2892-2L-e FP32 add latency 扫描。
# 用法：vivado -mode batch -source u55c_fadd_sweep.tcl -tclargs <output-dir> ?latency ...?

if {$argc < 1} {
  error "usage: u55c_fadd_sweep.tcl <output-dir> ?latency ...?"
}

set output_dir [file normalize [lindex $argv 0]]
if {$argc > 1} {
  set latencies [lrange $argv 1 end]
} else {
  set latencies {4 5 6 7 8 9 10 11}
}

file mkdir $output_dir
create_project -force u55c_fadd_sweep [file join $output_dir project] \
    -part xcu55c-fsvh2892-2L-e
set_property target_language Verilog [current_project]
set_property simulator_language Mixed [current_project]

set summary_path [file join $output_dir summary.csv]
set summary [open $summary_path w]
puts $summary "requested_latency,actual_latency,status,luts,ffs,dsps,wns_200_ns,wns_300_ns,estimated_fmax_mhz"

proc utilization_value {report label} {
  set pattern [format {\|[[:space:]]*%s[[:space:]]*\|[[:space:]]*([0-9,]+)[[:space:]]*\|} $label]
  if {[regexp $pattern $report -> value]} {
    return [string map {"," ""} $value]
  }
  return "NA"
}

proc worst_setup_slack {period} {
  # 不带 -add 重发 create_clock，会替换该端口上的现有时钟。
  create_clock -name aclk -period $period [get_ports aclk]
  update_timing
  set path [get_timing_paths -quiet -setup -max_paths 1 -nworst 1]
  if {[llength $path] == 0} {
    return "NA"
  }
  return [format %.3f [get_property SLACK $path]]
}

foreach requested_latency $latencies {
  if {![string is integer -strict $requested_latency] || $requested_latency < 0} {
    puts $summary "$requested_latency,NA,invalid,NA,NA,NA,NA,NA,NA"
    continue
  }

  set module_name "u55c_fadd_lat${requested_latency}"
  puts "INFO: creating $module_name"
  create_ip -name floating_point -version 7.1 -vendor xilinx.com -library ip \
      -module_name $module_name

  set status "ok"
  if {[catch {
    set_property -dict [list \
        CONFIG.a_precision_type Single \
        CONFIG.add_sub_value Add \
        CONFIG.c_a_exponent_width 8 \
        CONFIG.c_a_fraction_width 24 \
        CONFIG.c_has_divide_by_zero false \
        CONFIG.c_has_invalid_op false \
        CONFIG.c_has_overflow false \
        CONFIG.c_has_underflow false \
        CONFIG.c_latency $requested_latency \
        CONFIG.c_mult_usage Full_Usage \
        CONFIG.c_optimization Speed_Optimized \
        CONFIG.c_rate 1 \
        CONFIG.c_result_exponent_width 8 \
        CONFIG.c_result_fraction_width 24 \
        CONFIG.flow_control NonBlocking \
        CONFIG.has_aclken true \
        CONFIG.has_aresetn false \
        CONFIG.has_a_tlast false \
        CONFIG.has_a_tuser false \
        CONFIG.has_b_tlast false \
        CONFIG.has_b_tuser false \
        CONFIG.has_operation_tlast false \
        CONFIG.has_operation_tuser false \
        CONFIG.has_result_tready false \
        CONFIG.maximum_latency false \
        CONFIG.operation_type Add_Subtract \
        CONFIG.result_precision_type Single] [get_ips $module_name]
  } message]} {
    puts "WARNING: $module_name configuration failed: $message"
    puts $summary "$requested_latency,NA,config_failed,NA,NA,NA,NA,NA,NA"
    remove_files [get_files -quiet "*/${module_name}.xci"]
    continue
  }

  set actual_latency [get_property CONFIG.c_latency [get_ips $module_name]]
  set_property generate_synth_checkpoint false [get_files "*/${module_name}.xci"]
  generate_target synthesis [get_ips $module_name]

  if {[catch {
    synth_design -top $module_name -mode out_of_context -part xcu55c-fsvh2892-2L-e \
        -flatten_hierarchy rebuilt
  } message]} {
    puts "WARNING: $module_name synthesis failed: $message"
    puts $summary "$requested_latency,$actual_latency,synth_failed,NA,NA,NA,NA,NA,NA"
    close_design -quiet
    continue
  }

  set util_report [report_utilization -return_string]
  set luts [utilization_value $util_report {CLB LUTs\*}]
  set ffs [utilization_value $util_report "Register as Flip Flop"]
  set dsps [utilization_value $util_report "DSPs"]
  set wns_200 [worst_setup_slack 5.000]
  set wns_300 [worst_setup_slack 3.333]
  if {$wns_300 eq "NA"} {
    set fmax "NA"
  } else {
    set critical_period [expr {3.333 - double($wns_300)}]
    if {$critical_period > 0.0} {
      set fmax [format %.1f [expr {1000.0 / $critical_period}]]
    } else {
      set fmax "NA"
    }
  }

  report_utilization -file [file join $output_dir "lat${requested_latency}_utilization.rpt"]
  report_timing_summary -delay_type max -max_paths 20 -file \
      [file join $output_dir "lat${requested_latency}_timing_300mhz.rpt"]
  puts $summary "$requested_latency,$actual_latency,$status,$luts,$ffs,$dsps,$wns_200,$wns_300,$fmax"
  flush $summary
  close_design
}

close $summary
close_project
puts "INFO: summary written to $summary_path"
