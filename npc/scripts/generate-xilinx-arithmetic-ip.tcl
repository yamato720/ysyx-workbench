# Generate the arithmetic IP products used by the NPC stable arithmetic
# adapters. Run from Vivado, for example:
#
#   vivado -mode batch -source npc/scripts/generate-xilinx-arithmetic-ip.tcl \
#     -tclargs -part xczu9eg-ffvb1156-2-i -project-dir build/npc-arithmetic-ip
#
# The generated IP names deliberately do not leak into Chisel. Chisel binds to
# npc_int_multiplier_adapter and npc_int_divider_adapter.
# Those stable wrappers own req/resp tags, ready/valid backpressure and the
# mapping to the vendor ports. Keep wrapper RTL in the Vivado project beside
# the generated .xci files; do not add generated products to git.

proc option_value {name defaultValue} {
  global argv
  set index [lsearch -exact $argv $name]
  if {$index < 0} {
    return $defaultValue
  }
  if {$index + 1 >= [llength $argv]} {
    error "$name requires a value"
  }
  return [lindex $argv [expr {$index + 1}]]
}

set projectDir [file normalize [option_value -project-dir ./generated-ip/vivado]]
set part       [option_value -part xczu9eg-ffvb1156-2-i]
set xlen       [option_value -xlen 32]
set outputDir  [file normalize [option_value -output-dir ./generated-ip]]
set mulLatency [option_value -mul-latency 3]
set mulII      [option_value -mul-ii 1]
set divII      [option_value -div-ii 1]

if {$xlen != 32 && $xlen != 64} {
  error "-xlen must be 32 or 64"
}
foreach {name value} [list \
  -mul-latency $mulLatency -div-ii $divII] {
  if {![string is integer -strict $value] || $value < 1} {
    error "$name must be a positive integer"
  }
}
if {$mulII != 1} {
  error "Multiplier Generator is configured for II=1; -mul-ii must be 1"
}

file mkdir $projectDir
file mkdir $outputDir
create_project npc_arithmetic_ip $projectDir -part $part -force
set_property target_language Verilog [current_project]

# Vivado renamed a handful of CONFIG properties across IP releases. Every
# requested property is reported. A missing property stops the run, because a
# silently-defaulted arithmetic core would not match the timing contract used
# by the Chisel configuration.
proc set_required_config {ip property value} {
  if {[catch {set_property $property $value [get_ips $ip]} message]} {
    error "IP $ip does not support $property=$value: $message"
  }
  set actual [get_property $property [get_ips $ip]]
  if {$actual ne $value} {
    error "IP $ip rejected $property=$value (actual value: $actual)"
  }
}

proc make_ip {name vendor library ipName} {
  create_ip -name $name -vendor $vendor -library $library -module_name $ipName -dir [get_property DIRECTORY [current_project]]
}

# Integer multiplier adapter prepares signed/unsigned operands and preserves
# all RV32M/RV64M operation metadata. Width is XLEN+1 so one signed core can
# represent MULH, MULHSU and MULHU without changing the IP interface.
set multiplierWidth [expr {$xlen + 1}]
make_ip mult_gen xilinx.com ip npc_int_multiplier_ip
set_required_config npc_int_multiplier_ip CONFIG.PortAWidth $multiplierWidth
set_required_config npc_int_multiplier_ip CONFIG.PortBWidth $multiplierWidth
set_required_config npc_int_multiplier_ip CONFIG.Use_Custom_Output_Width true
set_required_config npc_int_multiplier_ip CONFIG.OutputWidthLow 0
set_required_config npc_int_multiplier_ip CONFIG.OutputWidthHigh [expr {2 * $multiplierWidth - 1}]
set_required_config npc_int_multiplier_ip CONFIG.PortAType Signed
set_required_config npc_int_multiplier_ip CONFIG.PortBType Signed
set_required_config npc_int_multiplier_ip CONFIG.Multiplier_Construction Use_Mults
set_required_config npc_int_multiplier_ip CONFIG.PipeStages $mulLatency

# The divider adapter performs RISC-V signed-magnitude conversion and the
# architectural divide-by-zero/overflow rules. The generated core therefore
# remains an unsigned quotient/remainder engine.
make_ip div_gen xilinx.com ip npc_int_divider_ip
set_required_config npc_int_divider_ip CONFIG.dividend_and_quotient_width $xlen
set_required_config npc_int_divider_ip CONFIG.divisor_width $xlen
set_required_config npc_int_divider_ip CONFIG.operand_sign Unsigned
set_required_config npc_int_divider_ip CONFIG.clocks_per_division $divII
set_required_config npc_int_divider_ip CONFIG.FlowControl Blocking
set_required_config npc_int_divider_ip CONFIG.OutTready true
set_required_config npc_int_divider_ip CONFIG.ARESETN true

set adapterRtl [file normalize [file join [file dirname [info script]] .. fpga-ip-generator common compute source sv npc-integer-ip-adapters.sv]]
if {![file exists $adapterRtl]} {
  error "missing stable adapter RTL: $adapterRtl"
}
add_files -norecurse $adapterRtl

generate_target all [get_ips]
export_ip_user_files -of_objects [get_ips] -no_script -sync -force -quiet

set manifest [open [file join $outputDir arithmetic-ip-manifest.tcl] w]
puts $manifest "set NPC_ARITH_XLEN $xlen"
puts $manifest "set NPC_MUL_CYCLES $mulLatency"
puts $manifest "set NPC_MUL_II $mulII"
puts $manifest "set NPC_DIV_CYCLES [get_property CONFIG.latency [get_ips npc_int_divider_ip]]"
puts $manifest "set NPC_DIV_II $divII"
puts $manifest "set NPC_INT_MULTIPLIER_IP npc_int_multiplier_ip"
puts $manifest "set NPC_INT_DIVIDER_IP npc_int_divider_ip"
close $manifest

set environmentManifest [open [file join $outputDir arithmetic-ip-manifest.env] w]
puts $environmentManifest "NPC_XLEN=$xlen"
puts $environmentManifest "NPC_MUL_CYCLES=$mulLatency"
puts $environmentManifest "NPC_MUL_II=$mulII"
puts $environmentManifest "NPC_DIV_CYCLES=[get_property CONFIG.latency [get_ips npc_int_divider_ip]]"
puts $environmentManifest "NPC_DIV_II=$divII"
close $environmentManifest

puts "Generated NPC arithmetic IP products in $projectDir"
puts "Added stable npc_*_adapter RTL and generated IP output products to the Vivado fileset."
puts "Use $outputDir/arithmetic-ip-manifest.env when elaborating the matching BlackBox configuration."
