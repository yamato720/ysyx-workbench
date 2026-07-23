# 为 NPC FPGA 构建生成实际接入数据通路的 Vivado 2022.2 算术 IP。
if {$argc != 9} {
  puts stderr "usage: create-arithmetic-ip.tcl OUT ACTUAL_MANIFEST IP_LOG_DIR PART XLEN MUL_LAT MUL_II DIV_IP_LAT DIV_II"
  exit 2
}

lassign $argv out_dir actual_manifest ip_log_dir part xlen mul_lat mul_ii div_ip_lat div_ii

file mkdir $out_dir
file mkdir $ip_log_dir
create_project npc_arithmetic_ip [file join $out_dir project] -part $part -force
set_property target_language Verilog [current_project]

proc set_if_supported {object property value} {
  if {[lsearch -exact [list_property $object] $property] >= 0} {
    set_property $property $value $object
  }
}

proc write_ip_log_header {log name kind width latency} {
  set stream [open $log w]
  puts $stream "IP=$name"
  puts $stream "KIND=$kind"
  puts $stream "WIDTH=$width"
  puts $stream "LATENCY=$latency"
  puts $stream ""
  close $stream
}

proc append_ip_log {log key value} {
  set stream [open $log a]
  puts $stream "$key=$value"
  close $stream
}

proc append_ip_property {log ip property} {
  if {[lsearch -exact [list_property $ip] $property] >= 0} {
    append_ip_log $log $property [get_property $property $ip]
  }
}

proc create_integer_ip {name kind width latency log_dir} {
  set log [file join $log_dir "$name.log"]
  write_ip_log_header $log $name $kind $width $latency
  if {$kind eq "multiply"} {
    create_ip -name mult_gen -vendor xilinx.com -library ip -module_name $name
    set ip [get_ips $name]
    # mult_gen 的单输入上限为 64 位。适配器以无符号 XLEN 乘积为基础，再按
    # RISC-V 操作码修正 MULH/MULHSU 的高半部，因此不需要 XLEN+1 位符号扩展。
    set_if_supported $ip CONFIG.PortAWidth $width
    set_if_supported $ip CONFIG.PortBWidth $width
    set_if_supported $ip CONFIG.Use_Custom_Output_Width true
    set_if_supported $ip CONFIG.OutputWidthLow 0
    set_if_supported $ip CONFIG.OutputWidthHigh [expr {2 * $width - 1}]
    set_if_supported $ip CONFIG.PortAType {Unsigned}
    set_if_supported $ip CONFIG.PortBType {Unsigned}
    set_if_supported $ip CONFIG.Multiplier_Construction {Use_Mults}
    set_if_supported $ip CONFIG.PipeStages $latency
  } else {
    create_ip -name div_gen -vendor xilinx.com -library ip -module_name $name
    set ip [get_ips $name]
    set_if_supported $ip CONFIG.dividend_and_quotient_width $width
    set_if_supported $ip CONFIG.divisor_width $width
    set_if_supported $ip CONFIG.operand_sign {Unsigned}
    set_if_supported $ip CONFIG.remainder_type {Remainder}
    set_if_supported $ip CONFIG.latency_configuration {Manual}
    set_if_supported $ip CONFIG.latency $latency
    set_if_supported $ip CONFIG.clocks_per_division 1
    set_if_supported $ip CONFIG.FlowControl {Blocking}
    set_if_supported $ip CONFIG.OutTready true
    set_if_supported $ip CONFIG.ARESETN true
  }
  append_ip_log $log ACTION generate_target_all
  generate_target all [get_ips $name]
  append_ip_log $log GENERATE_TARGET completed
  foreach property [list CONFIG.PipeStages CONFIG.PortAWidth CONFIG.PortBWidth \
      CONFIG.PortAType CONFIG.PortBType CONFIG.OutputWidthLow CONFIG.OutputWidthHigh \
      CONFIG.dividend_and_quotient_width CONFIG.divisor_width CONFIG.operand_sign \
      CONFIG.remainder_type CONFIG.latency_configuration CONFIG.latency \
      CONFIG.clocks_per_division CONFIG.FlowControl CONFIG.OutTready CONFIG.ARESETN] {
    append_ip_property $log $ip $property
  }
}

proc assert_property {ip property expected} {
  set actual [get_property $property $ip]
  if {$actual ne "$expected"} {
    puts stderr "IP property mismatch: $ip $property expected=$expected actual=$actual"
    exit 3
  }
}

create_integer_ip npc_int_multiplier_ip multiply $xlen $mul_lat $ip_log_dir
create_integer_ip npc_int_divider_ip divide $xlen $div_ip_lat $ip_log_dir

assert_property [get_ips npc_int_multiplier_ip] CONFIG.PipeStages $mul_lat
assert_property [get_ips npc_int_multiplier_ip] CONFIG.PortAWidth $xlen
assert_property [get_ips npc_int_multiplier_ip] CONFIG.PortBWidth $xlen
assert_property [get_ips npc_int_multiplier_ip] CONFIG.PortAType Unsigned
assert_property [get_ips npc_int_multiplier_ip] CONFIG.PortBType Unsigned
assert_property [get_ips npc_int_divider_ip] CONFIG.latency $div_ip_lat
assert_property [get_ips npc_int_divider_ip] CONFIG.OutTready true
assert_property [get_ips npc_int_divider_ip] CONFIG.ARESETN true

# RTL 适配器依赖 DivGen 文档规定的 AXIS 结果布局。该布局没有对应的 IP 属性，
# 因此通过生成的示例代码确认商和余数的打包顺序。
set divider_examples [glob -nocomplain -directory \
  [file join $out_dir project npc_arithmetic_ip.gen sources_1 ip npc_int_divider_ip demo_tb] *]
set divider_layout_ok false
foreach example $divider_examples {
  if {[file isfile $example]} {
    set stream [open $example r]
    set contents [read $stream]
    close $stream
    if {[regexp {remainder\s*<=\s*m_axis_dout_tdata\([^\n]*0\)} $contents] &&
        [regexp {quotient\s*<=\s*m_axis_dout_tdata\([^\n]*downto[^\n]*\)} $contents]} {
      set divider_layout_ok true
    }
  }
}
if {!$divider_layout_ok} {
  puts stderr "Unable to verify DivGen quotient/remainder AXIS packing"
  exit 3
}
set manifest [open $actual_manifest w]
puts $manifest "DIV_II=$div_ii"
puts $manifest "DIV_IP_LATENCY=[get_property CONFIG.latency [get_ips npc_int_divider_ip]]"
puts $manifest "MUL_II=$mul_ii"
puts $manifest "MUL_LATENCY=[get_property CONFIG.PipeStages [get_ips npc_int_multiplier_ip]]"
close $manifest

set ips [get_ips]
export_ip_user_files -of_objects $ips -no_script -sync -force -quiet
close_project
