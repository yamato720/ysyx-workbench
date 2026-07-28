// Elaboration-only stand-ins for vendor arithmetic IP. Interrupt regressions do
// not issue M-extension requests, so the models only need stable idle behavior.
module npc_int_multiplier_ip (
  input wire CLK,
  input wire [63:0] A,
  input wire [63:0] B,
  output wire [127:0] P
);
  assign P = A * B;
endmodule

module npc_int_divider_ip (
  input wire aclk,
  input wire aresetn,
  input wire s_axis_divisor_tvalid,
  output wire s_axis_divisor_tready,
  input wire [63:0] s_axis_divisor_tdata,
  input wire s_axis_dividend_tvalid,
  output wire s_axis_dividend_tready,
  input wire [63:0] s_axis_dividend_tdata,
  output wire m_axis_dout_tvalid,
  input wire m_axis_dout_tready,
  output wire [127:0] m_axis_dout_tdata
);
  assign s_axis_divisor_tready = 1'b1;
  assign s_axis_dividend_tready = 1'b1;
  assign m_axis_dout_tvalid = 1'b0;
  assign m_axis_dout_tdata = 128'b0;
endmodule
