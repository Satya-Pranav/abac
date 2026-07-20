// -----------------------------------------------------------------------------
// tb_skid_buffer.sv
//
// Self-checking testbench for skid_buffer.
//
// Stimulus phases (2000 cycles each):
//   1. random valid (50%) / random ready (50%)
//   2. full rate: valid 100% / ready 100%  -> also checks for throughput bubbles
//   3. heavy backpressure: valid 100% / ready 30%
//   4. sparse traffic: valid 30% / ready 100%
//
// Checks:
//   - In-order data integrity via an array scoreboard (push on ingress fire,
//     pop+compare on egress fire).
//   - m_valid/m_data held stable while m_ready is low.
//   - No egress beat without a matching ingress beat.
//   - No bubbles once the pipe is warm in the full-rate phase.
//   - Scoreboard fully drained at end of test.
//
// Run:
//   iverilog -g2012 -o skid_tb skid_buffer.sv tb_skid_buffer.sv && ./skid_tb
//   verilator --binary -j 0 --top tb_skid_buffer skid_buffer.sv tb_skid_buffer.sv
//   (or any SV-2012 simulator; optional waves with +define+DUMP)
// -----------------------------------------------------------------------------

`timescale 1ns/1ps

module tb_skid_buffer;

  localparam int unsigned DATA_W        = 16;
  localparam int          CYCLES_PER_PH = 2000;
  localparam int          SB_DEPTH      = 1 << 15;

  logic              clk;
  logic              rst_n;
  logic              s_valid, s_ready;
  logic              m_valid, m_ready;
  logic [DATA_W-1:0] s_data,  m_data;

  skid_buffer #(.DATA_W(DATA_W)) dut (
    .clk     (clk),
    .rst_n   (rst_n),
    .s_valid (s_valid),
    .s_ready (s_ready),
    .s_data  (s_data),
    .m_valid (m_valid),
    .m_ready (m_ready),
    .m_data  (m_data)
  );

  initial clk = 1'b0;
  always #5 clk = ~clk;

  // --------------------------------------------------------------------------
  // Stimulus control
  // --------------------------------------------------------------------------
  int unsigned p_valid   = 0;   // percent chance of offering a beat
  int unsigned p_ready   = 0;   // percent chance egress asserts ready
  int          phase     = 0;
  int          errors    = 0;
  bit          done_stim = 0;

  initial begin
    rst_n = 1'b0;
    repeat (5) @(posedge clk);
    rst_n = 1'b1;

    phase = 1; p_valid = 50;  p_ready = 50;  repeat (CYCLES_PER_PH) @(posedge clk);
    phase = 2; p_valid = 100; p_ready = 100; repeat (CYCLES_PER_PH) @(posedge clk);
    phase = 3; p_valid = 100; p_ready = 30;  repeat (CYCLES_PER_PH) @(posedge clk);
    phase = 4; p_valid = 30;  p_ready = 100; repeat (CYCLES_PER_PH) @(posedge clk);

    // Drain: stop issuing, keep egress ready.
    phase = 5; p_valid = 0; p_ready = 100;
    done_stim = 1;
    repeat (50) @(posedge clk);

    if (wr_ptr != rd_ptr) begin
      $display("ERROR: %0d beat(s) stuck in scoreboard after drain", wr_ptr - rd_ptr);
      errors++;
    end
    if (errors == 0)
      $display("PASS: %0d beats transferred, 0 errors", rd_ptr);
    else
      $display("FAIL: %0d beats transferred, %0d error(s)", rd_ptr, errors);
    $finish;
  end

  // Watchdog
  initial begin
    #(CYCLES_PER_PH * 4 * 10 * 20);
    $display("FAIL: watchdog timeout");
    $finish;
  end

  // --------------------------------------------------------------------------
  // Upstream driver: holds s_valid/s_data stable until the beat is accepted
  // --------------------------------------------------------------------------
  always @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
      s_valid <= 1'b0;
      s_data  <= '0;
    end else if (!s_valid || s_ready) begin        // free to change this cycle
      if (!done_stim && ($urandom_range(0, 99) < p_valid)) begin
        s_valid <= 1'b1;
        s_data  <= $urandom;
      end else begin
        s_valid <= 1'b0;
      end
    end
  end

  // --------------------------------------------------------------------------
  // Downstream backpressure
  // --------------------------------------------------------------------------
  always @(posedge clk or negedge rst_n) begin
    if (!rst_n) m_ready <= 1'b0;
    else        m_ready <= ($urandom_range(0, 99) < p_ready);
  end

  // --------------------------------------------------------------------------
  // Scoreboard + protocol checks (sampled at negedge: mid-cycle, race-free)
  // --------------------------------------------------------------------------
  logic [DATA_W-1:0] sb [0:SB_DEPTH-1];
  int wr_ptr = 0;
  int rd_ptr = 0;

  logic              stall_q  = 1'b0;
  logic [DATA_W-1:0] m_data_q = '0;
  int                warm_cnt = 0;

  always @(negedge clk) begin
    if (rst_n) begin
      // Ingress fire -> push expected data
      if (s_valid && s_ready) begin
        sb[wr_ptr % SB_DEPTH] = s_data;
        wr_ptr++;
      end

      // Egress fire -> pop and compare
      if (m_valid && m_ready) begin
        if (rd_ptr == wr_ptr) begin
          $display("ERROR @%0t: egress beat with empty scoreboard (data %h)",
                   $time, m_data);
          errors++;
        end else begin
          if (m_data !== sb[rd_ptr % SB_DEPTH]) begin
            $display("ERROR @%0t: beat %0d mismatch, got %h expected %h",
                     $time, rd_ptr, m_data, sb[rd_ptr % SB_DEPTH]);
            errors++;
          end
          rd_ptr++;
        end
      end

      // Egress output must hold through a stall
      if (stall_q) begin
        if (!m_valid) begin
          $display("ERROR @%0t: m_valid dropped during stall", $time);
          errors++;
        end else if (m_data !== m_data_q) begin
          $display("ERROR @%0t: m_data changed during stall (%h -> %h)",
                   $time, m_data_q, m_data);
          errors++;
        end
      end
      stall_q  <= m_valid && !m_ready;
      m_data_q <= m_data;

      // Full-rate phase: once warm, a beat must move every single cycle
      if (phase == 2) begin
        warm_cnt++;
        if (warm_cnt > 5 && !(m_valid && m_ready)) begin
          $display("ERROR @%0t: throughput bubble in full-rate phase", $time);
          errors++;
        end
      end else begin
        warm_cnt = 0;
      end
    end
  end

`ifdef DUMP
  initial begin
    $dumpfile("tb_skid_buffer.vcd");
    $dumpvars(0, tb_skid_buffer);
  end
`endif

endmodule
