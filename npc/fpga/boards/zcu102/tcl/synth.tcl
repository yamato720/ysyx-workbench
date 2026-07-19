if {$argc != 9} {
  puts stderr "usage: synth.tcl PROJECT PART TOP RTL_DIR BOARD_RTL_DIR IP_ADAPTER_RTL_DIR IP_DIR DCP SYNTH_JOBS"
  exit 2
}
lassign $argv project_dir part top rtl_dir board_rtl_dir ip_adapter_rtl_dir ip_dir checkpoint synth_jobs

proc recursive_files {directory pattern} {
  set matches {}
  foreach entry [glob -nocomplain -directory $directory *] {
    if {[file isdirectory $entry]} {
      set matches [concat $matches [recursive_files $entry $pattern]]
    } elseif {[string match $pattern [file tail $entry]]} {
      lappend matches $entry
    }
  }
  return $matches
}

proc add_rtl_tree {directory} {
  set files [concat [recursive_files $directory *.v] [recursive_files $directory *.sv]]
  if {[llength $files] == 0} {
    puts stderr "no Verilog/SystemVerilog sources found in $directory"
    exit 2
  }
  add_files -norecurse [lsort -unique $files]
}

create_project npc_zcu102 $project_dir -part $part -force
set_property target_language Verilog [current_project]
add_rtl_tree $rtl_dir
add_rtl_tree $board_rtl_dir
add_rtl_tree $ip_adapter_rtl_dir

set xci [recursive_files $ip_dir *.xci]
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
