if {$argc != 11} {
  puts stderr "usage: package-xo.tcl PROJECT PART TOP RTL_DIR BOARD_RTL_DIR IP_ADAPTER_RTL_DIR IP_DIR XO XLEN SYNTH_JOBS CLOCK_MHZ"
  exit 2
}
lassign $argv project_dir part top rtl_dir board_rtl_dir ip_adapter_rtl_dir ip_dir xo xlen synth_jobs clock_mhz

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

create_project npc_u55c $project_dir -part $part -force
set_property target_language Verilog [current_project]
add_rtl_tree $rtl_dir
add_rtl_tree $board_rtl_dir
add_rtl_tree $ip_adapter_rtl_dir
set_property verilog_define "NPC_FPGA_XLEN=$xlen" [current_fileset]

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
ipx::package_project -root_dir [file join $project_dir packaged] -vendor user.org -library RTLKernel -taxonomy /KernelIP -import_files
set core [ipx::current_core]
set_property core_revision 1 $core
set_property sdx_kernel true $core
set_property sdx_kernel_type rtl $core
set_property supported_families {} $core
set_property auto_family_support_level level_2 $core
ipx::infer_bus_interfaces $core
ipx::associate_bus_interfaces -busif m_axi_gmem -clock ap_clk $core
ipx::associate_bus_interfaces -busif s_axi_control -clock ap_clk $core
ipx::associate_bus_interfaces -clock ap_clk -reset ap_rst_n $core

set clock_interface [ipx::get_bus_interfaces ap_clk -of_objects $core]
set clock_frequency [ipx::add_bus_parameter -quiet FREQ_HZ $clock_interface]
set_property value [expr {$clock_mhz * 1000000}] $clock_frequency
set_property value_resolve_type user $clock_frequency

# XRT 使用该指针参数把 guest-memory BO 绑定到 m_axi_gmem；RTL 从 mailbox
# 的 0xf0/0xf4 偏移读取同一个 64 位值。
set memory_map [ipx::add_memory_map -quiet s_axi_control $core]
set address_block [ipx::add_address_block -quiet reg0 $memory_map]
set memory_base_register [ipx::add_register -quiet memory_host_base $address_block]
set_property address_offset 0xf0 $memory_base_register
set_property size 64 $memory_base_register
set memory_base_parameter [ipx::add_register_parameter -quiet ASSOCIATED_BUSIF $memory_base_register]
set_property value m_axi_gmem $memory_base_parameter
set_property slave_memory_map_ref s_axi_control [ipx::get_bus_interfaces s_axi_control -of_objects $core]

ipx::create_xgui_files $core
ipx::update_checksums $core
ipx::check_integrity -kernel $core
ipx::save_core $core
package_xo -force -ctrl_protocol user_managed -xo_path $xo -kernel_name $top -ip_directory [file join $project_dir packaged]
close_project
