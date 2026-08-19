if {$argc != 9} {
  puts stderr "usage: package-cuperflow-xo.tcl PROJECT PART TOP SOURCE_MANIFEST XO SYNTH_JOBS PLATFORM_CLOCK_MHZ CORE_CLOCK_MHZ PC_COUNT"
  exit 2
}
lassign $argv project_dir part top source_manifest xo synth_jobs platform_clock_mhz core_clock_mhz pc_count
if {$part ne "xcu55c-fsvh2892-2L-e"} { error "Cuperflow kernel requires xcu55c-fsvh2892-2L-e, got $part" }
if {$platform_clock_mhz != 300 || $core_clock_mhz != 250} {
  error "Cuperflow kernel requires 300 MHz DATA_CLK and 250 MHz core clock"
}
if {$pc_count != 16} { error "Cuperflow kernel requires 16 HBM masters, got $pc_count" }

proc load_source_manifest {manifest} {
  if {![file isfile $manifest]} { error "source manifest not found: $manifest" }
  set handle [open $manifest r]
  set content [read $handle]
  close $handle
  set result [dict create rtl {} xci {}]
  set synthesis_mode 0
  foreach line [split $content "\n"] {
    if {$line eq "MODE=synthesis"} { set synthesis_mode 1 }
    if {[string match "MODEL=*" $line]} { error "Cuperflow synthesis manifest contains a simulation model: $line" }
    foreach {prefix key} {RTL= rtl XCI= xci} {
      if {[string match "${prefix}*" $line]} {
        set path [string range $line [string length $prefix] end]
        if {![file isfile $path]} { error "manifest source not found: $path" }
        dict lappend result $key $path
      }
    }
  }
  if {!$synthesis_mode || [llength [dict get $result rtl]] == 0 || [llength [dict get $result xci]] != 1} {
    error "Cuperflow synthesis manifest must contain RTL and exactly one FP64 XCI"
  }
  return $result
}

create_project spmv_cuperflow_kernel $project_dir -part $part -force
set_property target_language Verilog [current_project]
set_param general.maxThreads $synth_jobs
set sources [load_source_manifest $source_manifest]
add_files -norecurse [dict get $sources rtl]
import_ip -files [dict get $sources xci]
generate_target synthesis [get_ips]
foreach ip [get_ips] { create_ip_run $ip }
set ip_runs [get_runs *_synth_1]
if {[llength $ip_runs] != 0} {
  launch_runs $ip_runs -jobs $synth_jobs
  foreach run $ip_runs { wait_on_run $run }
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
  if {$bus_name eq "s_axi_control"} { set_property interface_mode slave $bus }
  if {[string match "m_axi_pc*" $bus_name]} { set_property interface_mode master $bus }
}
for {set pc 0} {$pc < $pc_count} {incr pc} {
  set bus_name [format "m_axi_pc%02d" $pc]
  if {[llength [ipx::get_bus_interfaces $bus_name -of_objects $core]] != 1} {
    error "packaged Cuperflow kernel is missing AXI interface $bus_name"
  }
  ipx::associate_bus_interfaces -busif $bus_name -clock ap_clk $core
}
ipx::associate_bus_interfaces -busif s_axi_control -clock ap_clk $core
ipx::associate_bus_interfaces -clock ap_clk -reset ap_rst_n $core
set clock_interface [ipx::get_bus_interfaces ap_clk -of_objects $core]
set clock_frequency [ipx::add_bus_parameter -quiet FREQ_HZ $clock_interface]
set_property value [expr {$platform_clock_mhz * 1000000}] $clock_frequency
set_property value_resolve_type user $clock_frequency

set memory_map [ipx::add_memory_map -quiet s_axi_control $core]
set address_block [ipx::add_address_block -quiet reg0 $memory_map]
set_property range 4096 $address_block
set_property width 32 $address_block
foreach {register_name register_offset register_access} {
  CTRL 0x000 read-write
  GIER 0x004 read-write
  IP_IER 0x008 read-write
  IP_ISR 0x00c read-write
  product_checksum 0x100 read-only
  product_checksum_hi 0x104 read-only
  status 0x108 read-only
} {
  set control_register [ipx::add_register -quiet $register_name $address_block]
  set_property address_offset $register_offset $control_register
  set_property size 32 $control_register
  set_property access $register_access $control_register
}
for {set pc 0} {$pc < $pc_count} {incr pc} {
  set register_name [format "hbm_base_pc%02d" $pc]
  set bus_name [format "m_axi_pc%02d" $pc]
  set base_register [ipx::add_register -quiet $register_name $address_block]
  set_property address_offset [expr {0x10 + 8 * $pc}] $base_register
  set_property size 64 $base_register
  set associated_bus [ipx::add_register_parameter -quiet ASSOCIATED_BUSIF $base_register]
  set_property value $bus_name $associated_bus
}
set_property slave_memory_map_ref s_axi_control [ipx::get_bus_interfaces s_axi_control -of_objects $core]

set kernel_xml [file join $project_dir ${top}.kernel.xml]
set kernel_xml_file [open $kernel_xml w]
puts $kernel_xml_file {<?xml version="1.0" encoding="UTF-8"?>}
puts $kernel_xml_file {<root versionMajor="1" versionMinor="9">}
puts $kernel_xml_file [format {  <kernel name="%s" language="ip_c" vlnv="user.org:RTLKernel:%s:1.0" attributes="" preferredWorkGroupSizeMultiple="0" workGroupSize="1" interrupt="true" hwControlProtocol="ap_ctrl_hs">} $top $top]
puts $kernel_xml_file {    <ports>}
for {set pc 0} {$pc < $pc_count} {incr pc} {
  puts $kernel_xml_file [format {      <port name="m_axi_pc%02d" mode="master" range="0xFFFFFFFFFFFFFFFF" dataWidth="512" portType="addressable" base="0x0"/>} $pc]
}
puts $kernel_xml_file {      <port name="s_axi_control" mode="slave" range="0x1000" dataWidth="32" portType="addressable" base="0x0"/>}
puts $kernel_xml_file {    </ports>}
puts $kernel_xml_file {    <args>}
for {set pc 0} {$pc < $pc_count} {incr pc} {
  puts $kernel_xml_file [format {      <arg name="hbm_base_pc%02d" addressQualifier="1" id="%d" port="m_axi_pc%02d" size="0x8" offset="0x%x" hostOffset="0x0" hostSize="0x8" type="void*"/>} $pc $pc $pc [expr {0x10 + 8 * $pc}]]
}
puts $kernel_xml_file {    </args>}
puts $kernel_xml_file {  </kernel>}
puts $kernel_xml_file {</root>}
close $kernel_xml_file

ipx::create_xgui_files $core
ipx::update_checksums $core
ipx::check_integrity -kernel $core
ipx::save_core $core
package_xo -force -ctrl_protocol ap_ctrl_hs -xo_path $xo -kernel_name $top \
  -ip_directory [file join $project_dir packaged] -kernel_xml $kernel_xml
close_project
