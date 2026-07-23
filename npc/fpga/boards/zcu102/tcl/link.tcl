if {$argc != 27} {
  puts stderr "usage: link.tcl PROJECT PART BOARD_REPO BOARD_PART RTL_DIR BOARD_RTL_DIR IP_ADAPTER_RTL_DIR IP_DIR XLEN CLOCK_MHZ GUEST_MEMORY_BASE HOST_MEMORY_BASE CONTROL_BASE BIT XSA IMPL_JOBS IMPL_STRATEGY WNS_MIN_NS IMPLEMENTATION_REPORTS_TCL TIMING_MAX_PATHS TIMING_PATHS_PER_CLOCK REPORT_CONGESTION REPORT_CLOCK_UTILIZATION REPORT_CONTROL_SETS REPORT_HIGH_FANOUT_NETS REPORT_METHODOLOGY REPORT_QOR_SUGGESTIONS"
  exit 2
}
lassign $argv project_dir part board_repo board_part rtl_dir board_rtl_dir ip_adapter_rtl_dir ip_dir xlen clock_mhz guest_memory_base host_memory_base control_base bitstream xsa impl_jobs impl_strategy timing_wns_min implementation_reports_tcl timing_max_paths timing_paths_per_clock report_congestion report_clock_utilization report_control_sets report_high_fanout_nets report_methodology report_qor_suggestions

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

if {![file isdirectory $board_repo]} {
  puts stderr "ZCU102 board repository not found: $board_repo"
  exit 2
}
set_param board.repoPaths [list $board_repo]

# 单独提取板卡 preset。官方板卡文件指定 -2-e，而生产目标使用引脚兼容的 -2-i。
create_project npc_zcu102_board_preset "${project_dir}-board-preset" -part $part -force
set_property board_part $board_part [current_project]
create_bd_design preset_design
set preset_ps [create_bd_cell -type ip -vlnv xilinx.com:ip:zynq_ultra_ps_e:3.4 ps]
apply_bd_automation -rule xilinx.com:bd_rule:zynq_ultra_ps_e \
  -config {apply_board_preset "1"} $preset_ps
set read_only_ps_properties [list \
  CONFIG.Component_Name \
  CONFIG.PSU__M_AXI_GP0__FREQMHZ CONFIG.PSU__M_AXI_GP1__FREQMHZ CONFIG.PSU__M_AXI_GP2__FREQMHZ \
  CONFIG.PSU__NUM_F2P0__INTR__INPUTS CONFIG.PSU__NUM_F2P1__INTR__INPUTS \
  CONFIG.PSU__S_AXI_GP0__FREQMHZ CONFIG.PSU__S_AXI_GP1__FREQMHZ \
  CONFIG.PSU__S_AXI_GP2__FREQMHZ CONFIG.PSU__S_AXI_GP3__FREQMHZ \
  CONFIG.PSU__S_AXI_GP4__FREQMHZ CONFIG.PSU__S_AXI_GP5__FREQMHZ \
  CONFIG.PSU__S_AXI_GP6__FREQMHZ CONFIG.PSU__TSU__BUFG_PORT_LOOPBACK]
set ps_config {}
foreach property [list_property $preset_ps] {
  if {[string match "CONFIG.*" $property] &&
      [lsearch -exact $read_only_ps_properties $property] < 0} {
    set value [get_property $property $preset_ps]
    if {$value ne ""} { lappend ps_config $property $value }
  }
}
close_project

create_project npc_zcu102_platform $project_dir -part $part -force
set_property source_mgmt_mode All [current_project]
set_property target_language Verilog [current_project]
add_rtl_tree $rtl_dir
add_rtl_tree $board_rtl_dir
add_rtl_tree $ip_adapter_rtl_dir
set_property verilog_define "NPC_FPGA_XLEN=$xlen" [current_fileset]

set xci [recursive_files $ip_dir *.xci]
if {[llength $xci] != 0} {
  import_ip -files $xci
}
update_compile_order -fileset sources_1

create_bd_design npc_zcu102
set ps [create_bd_cell -type ip -vlnv xilinx.com:ip:zynq_ultra_ps_e:3.4 ps]
set_property -dict $ps_config $ps
set_property -dict [list \
  CONFIG.PSU__USE__M_AXI_GP0 1 \
  CONFIG.PSU__USE__M_AXI_GP1 0 \
  CONFIG.PSU__USE__S_AXI_GP0 1 \
  CONFIG.PSU__USE__IRQ0 1 \
  CONFIG.PSU__CRL_APB__PL0_REF_CTRL__FREQMHZ $clock_mhz] $ps

set pl [create_bd_cell -type module -reference NpcZcu102Pl pl]
set_property -dict [list \
  CONFIG.GUEST_MEMORY_BASE $guest_memory_base \
  CONFIG.HOST_MEMORY_BASE $host_memory_base] $pl

set memory_sc [create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 memory_sc]
set_property -dict [list CONFIG.NUM_SI 1 CONFIG.NUM_MI 1] $memory_sc
set control_sc [create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 control_sc]
set_property -dict [list CONFIG.NUM_SI 1 CONFIG.NUM_MI 1] $control_sc
set reset_block [create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 reset_block]
set constant_zero [create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 constant_zero]
set_property CONFIG.CONST_VAL 0 $constant_zero
set constant_one [create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 constant_one]
set_property CONFIG.CONST_VAL 1 $constant_one

connect_bd_intf_net [get_bd_intf_pins pl/M_AXI_MEMORY] [get_bd_intf_pins memory_sc/S00_AXI]
connect_bd_intf_net [get_bd_intf_pins memory_sc/M00_AXI] [get_bd_intf_pins ps/S_AXI_HPC0_FPD]
connect_bd_intf_net [get_bd_intf_pins ps/M_AXI_HPM0_FPD] [get_bd_intf_pins control_sc/S00_AXI]
connect_bd_intf_net [get_bd_intf_pins control_sc/M00_AXI] [get_bd_intf_pins pl/S_AXI_CONTROL]

connect_bd_net [get_bd_pins ps/pl_clk0] \
  [get_bd_pins ps/maxihpm0_fpd_aclk] \
  [get_bd_pins ps/saxihpc0_fpd_aclk] \
  [get_bd_pins memory_sc/aclk] \
  [get_bd_pins control_sc/aclk] \
  [get_bd_pins reset_block/slowest_sync_clk] \
  [get_bd_pins pl/ap_clk]
connect_bd_net [get_bd_pins ps/pl_resetn0] [get_bd_pins reset_block/ext_reset_in]
connect_bd_net [get_bd_pins constant_zero/dout] \
  [get_bd_pins reset_block/aux_reset_in] \
  [get_bd_pins reset_block/mb_debug_sys_rst]
connect_bd_net [get_bd_pins constant_one/dout] [get_bd_pins reset_block/dcm_locked]
connect_bd_net [get_bd_pins reset_block/peripheral_aresetn] \
  [get_bd_pins memory_sc/aresetn] \
  [get_bd_pins control_sc/aresetn] \
  [get_bd_pins pl/ap_rst_n]
connect_bd_net [get_bd_pins pl/interrupt] [get_bd_pins ps/pl_ps_irq0]

assign_bd_address -offset 0x00000000 -range 2G \
  -target_address_space [get_bd_addr_spaces pl/M_AXI_MEMORY] \
  [get_bd_addr_segs ps/SAXIGP0/HPC0_DDR_LOW] -force
set control_segments [get_bd_addr_segs -of_objects [get_bd_intf_pins pl/S_AXI_CONTROL]]
if {[llength $control_segments] != 1} {
  puts stderr "expected one mailbox address segment, found: $control_segments"
  exit 1
}
assign_bd_address -offset $control_base -range 64K \
  -target_address_space [get_bd_addr_spaces ps/Data] \
  [lindex $control_segments 0] -force

validate_bd_design
save_bd_design
if {[info exists ::env(NPC_FPGA_VALIDATE_ONLY)] && $::env(NPC_FPGA_VALIDATE_ONLY) eq "1"} {
  close_project
  exit 0
}
generate_target all [get_files npc_zcu102.bd]
set wrapper [make_wrapper -files [get_files npc_zcu102.bd] -top]
add_files -norecurse $wrapper
set_property top npc_zcu102_wrapper [current_fileset]
update_compile_order -fileset sources_1

set_property strategy $impl_strategy [get_runs impl_1]
launch_runs impl_1 -to_step write_bitstream -jobs $impl_jobs
wait_on_run impl_1
if {[get_property PROGRESS [get_runs impl_1]] ne "100%"} {
  puts stderr "ZCU102 implementation did not complete"
  exit 1
}
open_run impl_1
if {![file isfile $implementation_reports_tcl]} {
  puts stderr "implementation reports Tcl not found: $implementation_reports_tcl"
  exit 2
}
set npc_report_timing_max_paths $timing_max_paths
set npc_report_timing_paths_per_clock $timing_paths_per_clock
set npc_report_congestion $report_congestion
set npc_report_clock_utilization $report_clock_utilization
set npc_report_control_sets $report_control_sets
set npc_report_high_fanout_nets $report_high_fanout_nets
set npc_report_methodology $report_methodology
set npc_report_qor_suggestions $report_qor_suggestions
source $implementation_reports_tcl
# 保留原有邻近 bitstream 的报告位置，供已有人工分析和脚本直接访问。
file copy -force [file join $npc_implementation_report_dir timing-summary.rpt] \
  [file rootname $bitstream].timing.rpt
set timing_paths [get_timing_paths -delay_type max -max_paths 1]
if {[llength $timing_paths] != 1} {
  puts stderr "ZCU102 implementation produced no setup timing path"
  exit 1
}
set timing_file [open [file rootname $bitstream].wns w]
puts $timing_file [get_property SLACK [lindex $timing_paths 0]]
close $timing_file
set wns [get_property SLACK [lindex $timing_paths 0]]
if {$wns < $timing_wns_min} {
  puts stderr "ZCU102 timing WNS $wns ns is below configured minimum $timing_wns_min ns"
  exit 1
}
write_bitstream -force $bitstream
write_hw_platform -fixed -include_bit -force $xsa
close_project
