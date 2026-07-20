// -----------------------------------------------------------------------------
// skid_buffer.sv
//
// Full register slice ("skid buffer") for a valid/ready stream, used to close
// timing on the ready path coming back from egress.
//
//   ingress ---> [output reg] ---> egress
//                     ^
//                [skid reg]   (catches the one in-flight beat that lands
//                              in the cycle egress deasserts ready)
//
// All interface outputs are driven directly from flops:
//   s_ready : flop  (the egress m_ready path is cut here)
//   m_valid : flop
//   m_data  : flop
//
// Capacity   : 2 beats (output register + skid register)
// Throughput : 1 beat/cycle sustained, no bubbles
// Latency    : 1 cycle
//
// Protocol assumptions (standard valid/ready):
//   - Upstream holds s_valid/s_data stable until s_ready is seen high.
//   - No combinational dependence of s_valid on s_ready.
// Guarantees:
//   - m_valid/m_data held stable while m_ready is low.
//   - No beat dropped, duplicated, or reordered.
// -----------------------------------------------------------------------------

module skid_buffer #(
  parameter int unsigned DATA_W = 32
) (
  input  logic              clk,
  input  logic              rst_n,

  // Ingress (upstream)
  input  logic              s_valid,
  output logic              s_ready,
  input  logic [DATA_W-1:0] s_data,

  // Egress (downstream)
  output logic              m_valid,
  input  logic              m_ready,
  output logic [DATA_W-1:0] m_data
);

  // ---------------------------------------------------------------------------
  // Handshake helpers
  // ---------------------------------------------------------------------------
  logic s_fire;   // ingress beat accepted this cycle
  logic m_load;   // output register can take a new beat this cycle

  assign s_fire = s_valid && s_ready;
  assign m_load = !m_valid || m_ready;

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------
  logic              m_valid_q;
  logic [DATA_W-1:0] m_data_q;

  logic              skid_valid_q;
  logic [DATA_W-1:0] skid_data_q;

  logic              s_ready_q;

  // Invariant: s_ready_q == !skid_valid_q (both registered from the same
  // next-state below), so a beat can never arrive while the skid is full.
  logic skid_valid_d;

  always_comb begin
    skid_valid_d = skid_valid_q;
    if (m_load)
      skid_valid_d = 1'b0;              // skid drains into the output register
    else if (s_fire)
      skid_valid_d = 1'b1;              // egress stalled: catch the beat
  end

  always_ff @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
      m_valid_q    <= 1'b0;
      skid_valid_q <= 1'b0;
      s_ready_q    <= 1'b0;
    end else begin
      skid_valid_q <= skid_valid_d;
      s_ready_q    <= !skid_valid_d;
      if (m_load)
        m_valid_q  <= skid_valid_q || s_fire;
    end
  end

  // Datapath (no reset on data flops)
  always_ff @(posedge clk) begin
    if (m_load) begin
      if (skid_valid_q)
        m_data_q <= skid_data_q;
      else if (s_fire)
        m_data_q <= s_data;
    end
    if (s_fire && !m_load)
      skid_data_q <= s_data;
  end

  assign s_ready = s_ready_q;
  assign m_valid = m_valid_q;
  assign m_data  = m_data_q;

  // ---------------------------------------------------------------------------
  // Assertions (enable with +define+SKID_BUFFER_SVA in SVA-capable tools)
  // ---------------------------------------------------------------------------
`ifdef SKID_BUFFER_SVA
  // Never accept a beat while the skid register is occupied (no overflow).
  a_no_overflow : assert property (@(posedge clk) disable iff (!rst_n)
    s_fire |-> !skid_valid_q);

  // Egress output held stable across a stall cycle.
  a_m_stable : assert property (@(posedge clk) disable iff (!rst_n)
    m_valid && !m_ready |=> m_valid && $stable(m_data));

  // Registered-ready invariant.
  a_ready_inv : assert property (@(posedge clk) disable iff (!rst_n)
    s_ready == !skid_valid_q);

  // Protocol assumption on upstream: valid held until accepted.
  a_s_stable : assume property (@(posedge clk) disable iff (!rst_n)
    s_valid && !s_ready |=> s_valid && $stable(s_data));
`endif

endmodule
