if {$argc != 11} {
  puts stderr "usage: package-ooc-xo.tcl PROJECT PART TOP SOURCE_MANIFEST XO DCP REPORT_DIR SYNTH_JOBS CLOCK_PERIOD_NS PC_COUNT CLOCK_MHZ"
  exit 2
}
lassign $argv project_dir part top source_manifest xo dcp report_dir synth_jobs clock_period_ns pc_count clock_mhz
if {$part ne "xcu55c-fsvh2892-2L-e"} { error "SPMV resource probe requires xcu55c-fsvh2892-2L-e, got $part" }
set expected_period [format "%.3f" [expr {1000.0 / double($clock_mhz)}]]
if {$clock_period_ns ne $expected_period} { error "SPMV clock period $clock_period_ns does not match $clock_mhz MHz ($expected_period ns)" }
if {$pc_count != 32} { error "SPMV resource probe requires 32 HBM PCs, got $pc_count" }

proc load_source_manifest {manifest} {
  if {![file isfile $manifest]} { error "source manifest not found: $manifest" }
  set handle [open $manifest r]
  set content [read $handle]
  close $handle
  set rtl {}
  set synthesis_mode 0
  foreach line [split $content "\n"] {
    if {$line eq "MODE=synthesis"} { set synthesis_mode 1 }
    if {[string match "MODEL=*" $line] || [string match "XCI=*" $line]} {
      error "SPMV synthesis manifest contains an unsupported source: $line"
    }
    if {[string match "RTL=*" $line]} {
      set path [string range $line 4 end]
      if {![file isfile $path]} { error "manifest source not found: $path" }
      lappend rtl $path
    }
  }
  if {!$synthesis_mode || [llength $rtl] == 0} { error "invalid SPMV synthesis manifest" }
  return $rtl
}

file mkdir $report_dir
set_param general.maxThreads $synth_jobs
create_project spmv_resource_probe $project_dir -part $part -force
set_property target_language Verilog [current_project]
set sources [load_source_manifest $source_manifest]
add_files -norecurse $sources
set_property top $top [current_fileset]
update_compile_order -fileset sources_1

# OOC 综合直接约束 Vitis kernel 的 ap_clk；本探针不经过 Vitis link 或平台实现。
set clock_xdc [file join $project_dir spmv-resource-probe-clock.xdc]
set clock_file [open $clock_xdc w]
puts $clock_file "create_clock -name ap_clk -period $clock_period_ns \[get_ports ap_clk\]"
close $clock_file
add_files -fileset constrs_1 $clock_xdc

synth_design -top $top -part $part -flatten_hierarchy rebuilt
write_checkpoint -force $dcp
report_utilization -file [file join $report_dir spmv-utilization.rpt]
report_utilization -hierarchical -hierarchical_depth 6 -file [file join $report_dir spmv-utilization-hierarchical.rpt]
report_timing_summary -max_paths 50 -file [file join $report_dir spmv-timing-summary.rpt]

# XO 使用同一组已综合验证的 RTL；32 个 AXI master 均与 ap_clk 关联。
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
    error "packaged kernel is missing AXI interface $bus_name"
  }
  ipx::associate_bus_interfaces -busif $bus_name -clock ap_clk $core
}
ipx::associate_bus_interfaces -busif s_axi_control -clock ap_clk $core
ipx::associate_bus_interfaces -clock ap_clk -reset ap_rst_n $core
set clock_interface [ipx::get_bus_interfaces ap_clk -of_objects $core]
set clock_frequency [ipx::add_bus_parameter -quiet FREQ_HZ $clock_interface]
set_property value [expr {$clock_mhz * 1000000}] $clock_frequency
set_property value_resolve_type user $clock_frequency

set memory_map [ipx::add_memory_map -quiet s_axi_control $core]
set address_block [ipx::add_address_block -quiet reg0 $memory_map]
set_property range 4096 $address_block
set_property width 32 $address_block

# 将 RTL 中已有的 ap_ctrl_hs 与状态寄存器写入 IP-XACT，避免 XO 只暴露参数地址。
foreach {register_name register_offset register_access} {
  CTRL               0x000 read-write
  GIER               0x004 read-write
  IP_IER             0x008 read-write
  IP_ISR             0x00c read-write
  aggregate_checksum 0x110 read-only
  lane_done_mask     0x114 read-only
  lane_error_mask    0x118 read-only
  aggregate_checksum_hi 0x11c read-only
} {
  set control_register [ipx::add_register -quiet $register_name $address_block]
  set_property address_offset $register_offset $control_register
  set_property size 32 $control_register
  set_property access $register_access $control_register
}
for {set pc 0} {$pc < $pc_count} {incr pc} {
  set register_name [format "x_base_pc%02d" $pc]
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
  puts $kernel_xml_file [format {      <arg name="x_base_pc%02d" addressQualifier="1" id="%d" port="m_axi_pc%02d" size="0x8" offset="0x%x" hostOffset="0x0" hostSize="0x8" type="void*"/>} $pc $pc $pc [expr {0x10 + 8 * $pc}]]
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
