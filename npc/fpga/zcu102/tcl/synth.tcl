if {$argc != 6} {
  puts stderr "usage: synth.tcl PROJECT PART TOP SOURCE_MANIFEST DCP SYNTH_JOBS"
  exit 2
}
lassign $argv project_dir part top source_manifest checkpoint synth_jobs

proc load_source_manifest {manifest} {
  if {![file isfile $manifest]} { error "source manifest not found: $manifest" }
  set handle [open $manifest r]
  set content [read $handle]
  close $handle
  set result [dict create rtl {} xci {}]
  set synthesis_mode 0
  foreach line [split $content "\n"] {
    if {$line eq "MODE=synthesis"} { set synthesis_mode 1 }
    if {[string match "MODEL=*" $line]} { error "synthesis manifest contains simulation model: $line" }
    foreach {prefix key} {RTL= rtl XCI= xci} {
      if {[string match "${prefix}*" $line]} {
        set path [string range $line [string length $prefix] end]
        if {![file isfile $path]} { error "manifest source not found: $path" }
        dict lappend result $key $path
      }
    }
  }
  if {!$synthesis_mode} { error "source manifest is not synthesis mode" }
  if {[llength [dict get $result rtl]] == 0} { error "source manifest has no RTL" }
  return $result
}

create_project npc_zcu102 $project_dir -part $part -force
set_property target_language Verilog [current_project]
set sources [load_source_manifest $source_manifest]
add_files -norecurse [dict get $sources rtl]

set xci [dict get $sources xci]
if {[llength $xci] != 0} {
  import_ip -files $xci
  generate_target synthesis [get_ips]
  foreach ip [get_ips] { create_ip_run $ip }
  set ip_runs [get_runs *_synth_1]
  if {[llength $ip_runs] != 0} {
    launch_runs $ip_runs -jobs $synth_jobs
    foreach run $ip_runs { wait_on_run $run }
  }
}
set_property top $top [current_fileset]
update_compile_order -fileset sources_1
synth_design -top $top -part $part
write_checkpoint -force $checkpoint
close_project
