# 由板卡构造流程 source。调用方必须先设置 `npc_report_*` 变量，并在实现完成、
# 当前 implementation run 已打开时执行本脚本。

foreach variable {
  npc_report_timing_max_paths
  npc_report_timing_paths_per_clock
  npc_report_congestion
  npc_report_clock_utilization
  npc_report_control_sets
  npc_report_high_fanout_nets
  npc_report_methodology
  npc_report_qor_suggestions
} {
  if {![info exists $variable]} {
    error "implementation report setting '$variable' is not set"
  }
}

set npc_implementation_report_run [current_run]
if {[llength $npc_implementation_report_run] != 1} {
  error "implementation reports require exactly one current implementation run"
}
set npc_implementation_report_dir [file normalize [file join \
  [get_property DIRECTORY $npc_implementation_report_run] npc-implementation-reports]]
file mkdir $npc_implementation_report_dir
set npc_implementation_report_errors [open [file join $npc_implementation_report_dir report-errors.log] a]

proc npc_optional_implementation_report {name command} {
  global npc_implementation_report_errors
  if {[catch {uplevel #0 $command} message]} {
    puts stderr "WARNING: optional implementation report '$name' failed: $message"
    puts $npc_implementation_report_errors "$name: $message"
  }
}

# 时序摘要是硬性诊断证据：即使辅助报告不被当前 Vivado 版本支持，也必须保留它。
report_timing_summary \
  -file [file join $npc_implementation_report_dir timing-summary.rpt] \
  -max_paths $npc_report_timing_max_paths \
  -nworst $npc_report_timing_paths_per_clock \
  -report_unconstrained
npc_optional_implementation_report timing-paths [list report_timing \
  -file [file join $npc_implementation_report_dir timing-paths.rpt] \
  -max_paths $npc_report_timing_max_paths \
  -nworst $npc_report_timing_paths_per_clock \
  -delay_type max \
  -path_type full_clock_expanded]

if {$npc_report_congestion} {
  npc_optional_implementation_report congestion [list report_design_analysis \
    -congestion -file [file join $npc_implementation_report_dir congestion.rpt]]
}
if {$npc_report_clock_utilization} {
  npc_optional_implementation_report clock-utilization [list report_clock_utilization \
    -file [file join $npc_implementation_report_dir clock-utilization.rpt]]
}
if {$npc_report_control_sets} {
  npc_optional_implementation_report control-sets [list report_control_sets -verbose \
    -file [file join $npc_implementation_report_dir control-sets.rpt]]
}
if {$npc_report_high_fanout_nets} {
  npc_optional_implementation_report high-fanout-nets [list report_high_fanout_nets -timing \
    -file [file join $npc_implementation_report_dir high-fanout-nets.rpt]]
}
if {$npc_report_methodology} {
  npc_optional_implementation_report methodology [list report_methodology \
    -file [file join $npc_implementation_report_dir methodology.rpt]]
}
if {$npc_report_qor_suggestions} {
  npc_optional_implementation_report qor-suggestions [list report_qor_suggestions \
    -file [file join $npc_implementation_report_dir qor-suggestions.rpt]]
}

close $npc_implementation_report_errors
puts "INFO: implementation reports written to $npc_implementation_report_dir"
