if {$argc != 11} {
  puts stderr "usage: package-xo.tcl PROJECT PART TOP SOURCE_MANIFEST XO XLEN AXI_DATA_WIDTH SYNTH_JOBS PLATFORM_CLOCK_MHZ CORE_CLOCK_MHZ RUNTIME_TRACE"
  exit 2
}
lassign $argv project_dir part top source_manifest xo xlen axi_data_width synth_jobs platform_clock_mhz core_clock_mhz runtime_trace
if {$runtime_trace ni {0 1}} { error "RUNTIME_TRACE must be 0 or 1" }
if {$axi_data_width < $xlen || ($axi_data_width & ($axi_data_width - 1)) != 0} {
  error "U55C AXI data width must be a power of two no narrower than XLEN, got $axi_data_width"
}
if {$platform_clock_mhz != 300} { error "U55C platform DATA_CLK must be 300 MHz, got $platform_clock_mhz" }
if {$core_clock_mhz ni {100 125 150 200 250 300}} {
  error "U55C core clock must be one of 100, 125, 150, 200, 250, 300 MHz, got $core_clock_mhz"
}
if {$core_clock_mhz > $platform_clock_mhz} {
  error "U55C core clock $core_clock_mhz MHz exceeds platform DATA_CLK $platform_clock_mhz MHz"
}

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

create_project npc_u55c $project_dir -part $part -force
set_property target_language Verilog [current_project]
set sources [load_source_manifest $source_manifest]
# ipx::package_project imports sources into a new IP project and does not retain
# this project's verilog_define setting.  Materialize the XLEN define in the
# wrapper itself so both synthesis and Vitis' later IP recompilation see the
# same AXI data width.
set packaging_rtl {}
set packaged_wrapper 0
foreach source [dict get $sources rtl] {
  if {[file tail $source] eq "npc-u55c-kernel-wrapper.sv"} {
    set packaged_wrapper_source [file join $project_dir npc-u55c-kernel-wrapper.sv]
    set source_file [open $source r]
    set source_text [read $source_file]
    close $source_file
    set wrapper_file [open $packaged_wrapper_source w]
    puts $wrapper_file "`define NPC_FPGA_XLEN $xlen"
    puts $wrapper_file "`define NPC_FPGA_AXI_DATA_WIDTH $axi_data_width"
    puts $wrapper_file "`define NPC_FPGA_PLATFORM_CLOCK_MHZ $platform_clock_mhz"
    puts $wrapper_file "`define NPC_FPGA_CORE_CLOCK_MHZ $core_clock_mhz"
    if {$runtime_trace == 1} { puts $wrapper_file "`define NPC_FPGA_CLOCKED_CORE 1" }
    if {$runtime_trace == 1} { puts $wrapper_file "`define NPC_FPGA_RUNTIME_TRACE 1" }
    puts -nonewline $wrapper_file $source_text
    close $wrapper_file
    lappend packaging_rtl $packaged_wrapper_source
    set packaged_wrapper 1
  } else {
    lappend packaging_rtl $source
  }
}
if {!$packaged_wrapper} { error "synthesis manifest has no U55C kernel wrapper" }
add_files -norecurse $packaging_rtl
set_property verilog_define [list "NPC_FPGA_XLEN=$xlen" "NPC_FPGA_AXI_DATA_WIDTH=$axi_data_width"] [current_fileset]

if {$runtime_trace == 1} {
  # Chisel 7.0.0-M2 cannot emit a memory attribute with this project's
  # dependency set.  Constrain the explicitly named SyncReadMem here so the
  # v13 FIFO is implemented in U55C URAM rather than BRAM or registers.
  set trace_uram_xdc [file join $project_dir trace-uram.xdc]
  set trace_uram_file [open $trace_uram_xdc w]
  puts $trace_uram_file {set_property RAM_STYLE ultra [get_cells -hier -filter {NAME =~ *performance_monitor_uram_fifo*}]}
  close $trace_uram_file
  add_files -fileset constrs_1 $trace_uram_xdc
}

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
ipx::package_project -root_dir [file join $project_dir packaged] -vendor user.org -library RTLKernel -taxonomy /KernelIP -import_files
set core [ipx::current_core]
set_property core_revision 1 $core
set_property sdx_kernel true $core
set_property sdx_kernel_type rtl $core
set_property vitis_drc {ctrl_protocol ap_ctrl_hs} $core
set_property supported_families {} $core
set_property auto_family_support_level level_2 $core
ipx::infer_bus_interfaces $core
foreach bus [ipx::get_bus_interfaces -of_objects $core] {
  set bus_name [get_property NAME $bus]
  if {$bus_name eq "s_axi_control"} {
    set_property interface_mode slave $bus
  }
  if {[string match "m_axi_*" $bus_name]} {
    set_property interface_mode master $bus
  }
}
ipx::associate_bus_interfaces -busif m_axi_gmem -clock ap_clk $core
if {$runtime_trace == 1} { ipx::associate_bus_interfaces -busif m_axi_trace -clock ap_clk $core }
ipx::associate_bus_interfaces -busif s_axi_control -clock ap_clk $core
ipx::associate_bus_interfaces -clock ap_clk -reset ap_rst_n $core

set clock_interface [ipx::get_bus_interfaces ap_clk -of_objects $core]
set clock_frequency [ipx::add_bus_parameter -quiet FREQ_HZ $clock_interface]
set_property value [expr {$platform_clock_mhz * 1000000}] $clock_frequency
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

# Let Vitis derive the final xclbin CU metadata from the actual mailbox ABI,
# rather than the IP packager's generic RTL defaults.  XRT rejects a context
# whose control-window description is not a normal 4 KiB ap_ctrl_hs interface.
set kernel_xml [file join $project_dir ${top}.kernel.xml]
set kernel_xml_file [open $kernel_xml w]
puts $kernel_xml_file {<?xml version="1.0" encoding="UTF-8"?>}
puts $kernel_xml_file {<root versionMajor="1" versionMinor="9">}
puts $kernel_xml_file [format {  <kernel name="%s" language="ip_c" vlnv="user.org:RTLKernel:%s:1.0" attributes="" preferredWorkGroupSizeMultiple="0" workGroupSize="1" interrupt="true" hwControlProtocol="ap_ctrl_hs">} $top $top]
puts $kernel_xml_file {    <ports>}
puts $kernel_xml_file [format {      <port name="m_axi_gmem" mode="master" range="0xFFFFFFFFFFFFFFFF" dataWidth="%s" portType="addressable" base="0x0"/>} $axi_data_width]
if {$runtime_trace == 1} {
  puts $kernel_xml_file {      <port name="m_axi_trace" mode="master" range="0xFFFFFFFFFFFFFFFF" dataWidth="256" portType="addressable" base="0x0"/>}
}
puts $kernel_xml_file {      <port name="s_axi_control" mode="slave" range="0x1000" dataWidth="32" portType="addressable" base="0x0"/>}
puts $kernel_xml_file {    </ports>}
puts $kernel_xml_file {    <args>}
puts $kernel_xml_file {      <arg name="memory_host_base" addressQualifier="1" id="0" port="m_axi_gmem" size="0x8" offset="0xf0" hostOffset="0x0" hostSize="0x8" type="void*"/>}
if {$runtime_trace == 1} {
  puts $kernel_xml_file {      <arg name="trace_host_base" addressQualifier="1" id="1" port="m_axi_trace" size="0x8" offset="0x120" hostOffset="0x0" hostSize="0x8" type="void*"/>}
}
puts $kernel_xml_file {    </args>}
puts $kernel_xml_file {  </kernel>}
puts $kernel_xml_file {</root>}
close $kernel_xml_file

ipx::create_xgui_files $core
ipx::update_checksums $core
ipx::check_integrity -kernel $core
ipx::save_core $core
package_xo -force -ctrl_protocol ap_ctrl_hs -xo_path $xo -kernel_name $top -ip_directory [file join $project_dir packaged] -kernel_xml $kernel_xml
close_project
