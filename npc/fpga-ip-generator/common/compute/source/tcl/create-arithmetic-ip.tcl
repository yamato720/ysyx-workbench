# 根据 construction profile 生成或复用 NPC 使用的 Vivado 2022.2 整数算术 IP。
if {$argc != 4} {
  puts stderr "usage: create-arithmetic-ip.tcl OUT ACTUAL_MANIFEST IP_LOG_DIR PROFILE"
  exit 2
}

lassign $argv out_dir actual_manifest ip_log_dir profile_path

proc fail {message} {
  puts stderr $message
  exit 3
}

proc read_profile {path} {
  if {![file isfile $path]} {
    fail "FPGA profile does not exist: $path"
  }
  set stream [open $path r]
  set content [read $stream]
  close $stream
  set result [dict create]
  foreach line [split $content "\n"] {
    if {$line eq ""} {
      continue
    }
    set separator [string first "=" $line]
    if {$separator <= 0} {
      fail "Malformed FPGA profile line: $line"
    }
    set key [string range $line 0 [expr {$separator - 1}]]
    set value [string range $line [expr {$separator + 1}] end]
    if {![regexp {^[A-Z][A-Z0-9_]*$} $key]} {
      fail "Invalid FPGA profile key: $key"
    }
    dict set result $key $value
  }
  return $result
}

proc require_profile {profile key} {
  if {![dict exists $profile $key]} {
    fail "FPGA profile is missing $key"
  }
  return [dict get $profile $key]
}

proc require_positive_integer {name value} {
  if {![string is integer -strict $value] || $value <= 0} {
    fail "$name must be a positive integer, got $value"
  }
}

proc require_equal {name actual expected} {
  if {$actual ne "$expected"} {
    fail "$name mismatch: expected=$expected actual=$actual"
  }
}

set profile [read_profile $profile_path]
set board [require_profile $profile FPGA_BOARD]
set part [require_profile $profile FPGA_PART]
set xlen [require_profile $profile XLEN]
set mul_lat [require_profile $profile MUL_CYCLES]
set mul_ii [require_profile $profile MUL_II]
set div_lat [require_profile $profile DIV_CYCLES]
set div_ii [require_profile $profile DIV_II]
set div_ip_lat [require_profile $profile FPGA_DIV_IP_CYCLES]
set div_adapter_lat [require_profile $profile FPGA_DIV_ADAPTER_CYCLES]

foreach {name value} [list XLEN $xlen MUL_CYCLES $mul_lat MUL_II $mul_ii \
    DIV_CYCLES $div_lat DIV_II $div_ii FPGA_DIV_IP_CYCLES $div_ip_lat] {
  require_positive_integer $name $value
}
if {![string is integer -strict $div_adapter_lat] || $div_adapter_lat < 0} {
  fail "FPGA_DIV_ADAPTER_CYCLES must be a nonnegative integer, got $div_adapter_lat"
}
require_equal DIV_CYCLES $div_lat [expr {$div_ip_lat + $div_adapter_lat}]
require_equal MUL_II $mul_ii 1
require_equal DIV_II $div_ii 1

set multiplier_route "vendor-ip:npc_int_multiplier_adapter:$xlen:$mul_lat:$mul_ii:none"
foreach operation {MUL MULH MULHSU MULHU MULW} {
  set key "OPERATOR_ROUTE_M_$operation"
  require_equal $key [require_profile $profile $key] $multiplier_route
}
set divider_route "vendor-ip:npc_int_divider_adapter:$xlen:$div_lat:$div_ii:none"
foreach operation {DIV DIVU REM REMU DIVW DIVUW REMW REMUW} {
  set key "OPERATOR_ROUTE_M_$operation"
  require_equal $key [require_profile $profile $key] $divider_route
}

file mkdir $out_dir
file mkdir $ip_log_dir
set generated_root [file join $out_dir generated]
set project_dir [file join $out_dir .vivado-project]
file mkdir $generated_root
create_project npc_arithmetic_ip $project_dir -part $part -force
set_property target_language Verilog [current_project]

proc set_required_property {object property value} {
  if {[lsearch -exact [list_property $object] $property] < 0} {
    fail "Vivado IP $object does not expose required property $property"
  }
  set_property $property $value $object
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

proc expected_properties {kind width latency} {
  if {$kind eq "multiply"} {
    return [list \
      CONFIG.PortAWidth $width \
      CONFIG.PortBWidth $width \
      CONFIG.Use_Custom_Output_Width true \
      CONFIG.OutputWidthLow 0 \
      CONFIG.OutputWidthHigh [expr {2 * $width - 1}] \
      CONFIG.PortAType Unsigned \
      CONFIG.PortBType Unsigned \
      CONFIG.Multiplier_Construction Use_Mults \
      CONFIG.PipeStages $latency]
  }
  return [list \
    CONFIG.dividend_and_quotient_width $width \
    CONFIG.divisor_width $width \
    CONFIG.operand_sign Unsigned \
    CONFIG.remainder_type Remainder \
    CONFIG.latency_configuration Manual \
    CONFIG.latency $latency \
    CONFIG.clocks_per_division 1 \
    CONFIG.FlowControl Blocking \
    CONFIG.OutTready true \
    CONFIG.ARESETN true]
}

proc properties_match {ip expected} {
  foreach {property value} $expected {
    if {[lsearch -exact [list_property $ip] $property] < 0 ||
        [get_property $property $ip] ne "$value"} {
      return false
    }
  }
  return true
}

proc configure_integer_ip {ip kind width latency} {
  if {$kind eq "multiply"} {
    # mult_gen 的单输入上限为 64 位。适配器基于无符号 XLEN 乘积修正高半部。
    set_required_property $ip CONFIG.PortAWidth $width
    set_required_property $ip CONFIG.PortBWidth $width
    set_required_property $ip CONFIG.Use_Custom_Output_Width true
    set_required_property $ip CONFIG.OutputWidthLow 0
    set_required_property $ip CONFIG.OutputWidthHigh [expr {2 * $width - 1}]
    set_required_property $ip CONFIG.PortAType Unsigned
    set_required_property $ip CONFIG.PortBType Unsigned
    set_required_property $ip CONFIG.Multiplier_Construction Use_Mults
    set_required_property $ip CONFIG.PipeStages $latency
  } else {
    set_required_property $ip CONFIG.dividend_and_quotient_width $width
    set_required_property $ip CONFIG.divisor_width $width
    set_required_property $ip CONFIG.operand_sign Unsigned
    set_required_property $ip CONFIG.remainder_type Remainder
    set_required_property $ip CONFIG.latency_configuration Manual
    set_required_property $ip CONFIG.latency $latency
    set_required_property $ip CONFIG.clocks_per_division 1
    set_required_property $ip CONFIG.FlowControl Blocking
    set_required_property $ip CONFIG.OutTready true
    set_required_property $ip CONFIG.ARESETN true
  }
}

proc create_or_reuse_integer_ip {name kind width latency generated_root log_dir} {
  set log [file join $log_dir "$name.log"]
  set ip_dir [file join $generated_root $name]
  set xci [file join $ip_dir "$name.xci"]
  set expected [expected_properties $kind $width $latency]
  set ip ""
  write_ip_log_header $log $name $kind $width $latency

  if {[file isfile $xci]} {
    read_ip $xci
    set ip [get_ips $name]
    if {[properties_match $ip $expected]} {
      append_ip_log $log ACTION reuse
      foreach {property value} $expected {
        append_ip_property $log $ip $property
      }
      return $ip
    }
    append_ip_log $log ACTION regenerate_property_mismatch
  } else {
    append_ip_log $log ACTION generate_missing
  }

  if {$ip eq ""} {
    if {$kind eq "multiply"} {
      create_ip -name mult_gen -vendor xilinx.com -library ip -module_name $name -dir $generated_root
    } else {
      create_ip -name div_gen -vendor xilinx.com -library ip -module_name $name -dir $generated_root
    }
    set ip [get_ips $name]
  }
  configure_integer_ip $ip $kind $width $latency
  catch {reset_target all $ip}
  append_ip_log $log ACTION generate_target_all
  generate_target all $ip
  append_ip_log $log GENERATE_TARGET completed
  foreach {property value} $expected {
    append_ip_property $log $ip $property
  }
  return $ip
}

proc assert_properties {ip expected} {
  foreach {property value} $expected {
    if {[lsearch -exact [list_property $ip] $property] < 0} {
      fail "IP property missing: $ip $property"
    }
    require_equal "$ip $property" [get_property $property $ip] $value
  }
}

proc collect_files {directory} {
  set result [list]
  foreach path [glob -nocomplain -directory $directory *] {
    if {[file isdirectory $path]} {
      set result [concat $result [collect_files $path]]
    } else {
      lappend result $path
    }
  }
  return $result
}

set multiplier_ip [create_or_reuse_integer_ip npc_int_multiplier_ip multiply \
  $xlen $mul_lat $generated_root $ip_log_dir]
set divider_ip [create_or_reuse_integer_ip npc_int_divider_ip divide \
  $xlen $div_ip_lat $generated_root $ip_log_dir]
assert_properties $multiplier_ip [expected_properties multiply $xlen $mul_lat]
assert_properties $divider_ip [expected_properties divide $xlen $div_ip_lat]

# RTL 适配器依赖 DivGen 文档规定的 AXIS 商/余数打包顺序；用输出产品再次确认。
set divider_layout_ok false
foreach example [collect_files [file join $generated_root npc_int_divider_ip]] {
  if {[file isfile $example]} {
    set stream [open $example r]
    set contents [read $stream]
    close $stream
    if {[regexp {remainder\s*<=\s*m_axis_dout_tdata\([^\n]*0\)} $contents] &&
        [regexp {quotient\s*<=\s*m_axis_dout_tdata\([^\n]*downto[^\n]*\)} $contents]} {
      set divider_layout_ok true
      break
    }
  }
}
if {!$divider_layout_ok} {
  fail "Unable to verify DivGen quotient/remainder AXIS packing"
}

set manifest [open $actual_manifest w]
puts $manifest "BOARD=$board"
puts $manifest "DIV_II=$div_ii"
puts $manifest "DIV_IP_LATENCY=[get_property CONFIG.latency $divider_ip]"
puts $manifest "MUL_II=$mul_ii"
puts $manifest "MUL_LATENCY=[get_property CONFIG.PipeStages $multiplier_ip]"
puts $manifest "PART=[get_property PART [current_project]]"
puts $manifest "XLEN=$xlen"
close $manifest
close_project
