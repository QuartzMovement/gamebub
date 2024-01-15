module top_handheld (
    input clk_50mhz,

    input mcu_spi_clk,
    input mcu_spi_cs_n,
    inout mcu_spi_pico,
    inout mcu_spi_poci,
    inout mcu_spi_d2,
    inout mcu_spi_d3,
    inout mcu_irq_n,

    input btn_a,
    input btn_b,
    input btn_x,
    input btn_y,
    input btn_up,
    input btn_down,
    input btn_left,
    input btn_right,
    input btn_l,
    input btn_r,
    input btn_start,
    input btn_select,

    output dac_mclk,
    output dac_bclk,
    output dac_wclk,
    output dac_din,

    output lcd_dotclk, 
    output lcd_hsync,
    output lcd_vsync,
    output lcd_data_en,
    output [17:0] lcd_db,

    inout [7:0] cart_bank0,
    inout [7:0] cart_bank1,
    inout [7:0] cart_bank2,
    inout [3:0] cart_bank3,
    inout cart_pin30,
    output cart_pin30_dir,
    inout cart_pin31,
    output cart_pin31_dir,
    output cart_bank0_dir,
    output cart_bank1_dir,
    output cart_bank2_dir,
    output cart_bank3_dir,
    input cart_switch,
    output cart_en_3v3,
    output cart_en_5v0,
    output cart_oe_n,

    inout link_so,
    inout link_si,
    inout link_sd,
    inout link_sc,
    output link_so_dir,
    output link_si_dir,
    output link_sd_dir,
    output link_sc_dir,

    inout [3:0] pmod,
    output vibrate_en
);
    logic pll_reset = 1'd0;
    logic reset = 1'd0;

    logic clk_12mhz;
    logic clk_8mhz;
    logic pll0_locked;
    logic pll1_locked;

    // Manually construct IBUF for 50Mhz input clock to share between two clocking wizards.
    wire clk_in_50mhz;
      IBUF clkin1_ibufg
       (.O (clk_in_50mhz),
        .I (clk_50mhz));
    clk_wiz_0_clk_wiz clk_wiz_0(
        .reset(pll_reset),
        .locked(pll1_locked),
        .clk_in_50mhz(clk_in_50mhz),
        .clk_out_12mhz(clk_12mhz)
    );
    clk_wiz_1_clk_wiz clk_wiz_1(
        .reset(pll_reset),
        .locked(pll0_locked),
        .clk_in_50mhz(clk_in_50mhz),
        .clk_out_8mhz(clk_8mhz)
    );

    logic [7:0] inner_cart_bank0_in;
    logic [7:0] inner_cart_bank1_in;
    logic [7:0] inner_cart_bank2_in;
    logic [3:0] inner_cart_bank3_in;
    logic inner_cart_pin30_in;
    logic inner_cart_pin31_in;
    logic [7:0] inner_cart_bank0_out;
    logic [7:0] inner_cart_bank1_out;
    logic [7:0] inner_cart_bank2_out;
    logic [3:0] inner_cart_bank3_out;
    logic inner_cart_pin30_out;
    logic inner_cart_pin31_out;

    logic [3:0] inner_pmod_in;
    logic [3:0] inner_pmod_out;
    logic [3:0] inner_pmod_dir;

    logic inner_link_so_in;
    logic inner_link_si_in;
    logic inner_link_sd_in;
    logic inner_link_sc_in;
    logic inner_link_so_out;
    logic inner_link_si_out;
    logic inner_link_sd_out;
    logic inner_link_sc_out;

    HandheldTop handheld_top(
        .clock(clk_12mhz),
        .reset(reset),
        .io_clk_8mhz(clk_8mhz),

        .io_lcd_vsync(lcd_vsync),
        .io_lcd_hsync(lcd_hsync),
        .io_lcd_enable(lcd_data_en),
        .io_lcd_dotclk(lcd_dotclk),
        .io_lcdData(lcd_db),

        .io_dac_mclk(dac_mclk),
        .io_dac_wclk(dac_wclk),
        .io_dac_bclk(dac_bclk),
        .io_dac_data(dac_din),

        .io_buttons_a(btn_a),
        .io_buttons_b(btn_b),
        .io_buttons_x(btn_x),
        .io_buttons_y(btn_y),
        .io_buttons_up(btn_up),
        .io_buttons_down(btn_down),
        .io_buttons_left(btn_left),
        .io_buttons_right(btn_right),
        .io_buttons_l(btn_l),
        .io_buttons_r(btn_r),
        .io_buttons_start(btn_start),
        .io_buttons_select(btn_select),

        .io_cartridgeSwitch(cart_switch),
        .io_cartridge3V3Enable(cart_en_3v3),
        .io_cartridge5V0Enable(cart_en_5v0),
        .io_cartridgeOutputEnableN(cart_oe_n),
        .io_cartridge_bank0In(inner_cart_bank0_in),
        .io_cartridge_bank1In(inner_cart_bank1_in),
        .io_cartridge_bank2In(inner_cart_bank2_in),
        .io_cartridge_bank3In(inner_cart_bank3_in),
        .io_cartridge_pin30In(inner_cart_pin30_in),
        .io_cartridge_pin31In(inner_cart_pin31_in),
        .io_cartridge_bank0Out(inner_cart_bank0_out),
        .io_cartridge_bank1Out(inner_cart_bank1_out),
        .io_cartridge_bank2Out(inner_cart_bank2_out),
        .io_cartridge_bank3Out(inner_cart_bank3_out),
        .io_cartridge_pin30Out(inner_cart_pin30_out),
        .io_cartridge_pin31Out(inner_cart_pin31_out),
        .io_cartridge_bank0Dir(cart_bank0_dir),
        .io_cartridge_bank1Dir(cart_bank1_dir),
        .io_cartridge_bank2Dir(cart_bank2_dir),
        .io_cartridge_bank3Dir(cart_bank3_dir),
        .io_cartridge_pin30Dir(cart_pin30_dir),
        .io_cartridge_pin31Dir(cart_pin31_dir),

        .io_link_soIn(inner_link_so_in),
        .io_link_siIn(inner_link_si_in),
        .io_link_sdIn(inner_link_sd_in),
        .io_link_scIn(inner_link_sc_in),
        .io_link_soOut(inner_link_so_out),
        .io_link_siOut(inner_link_si_out),
        .io_link_sdOut(inner_link_sd_out),
        .io_link_scOut(inner_link_sc_out),
        .io_link_soDir(link_so_dir),
        .io_link_siDir(link_si_dir),
        .io_link_sdDir(link_sd_dir),
        .io_link_scDir(link_sc_dir),

        .io_pmod_in(inner_pmod_in),
        .io_pmod_out(inner_pmod_out),
        .io_pmod_dir(inner_pmod_dir),

        .io_vibrate(vibrate_en)
    );

    assign inner_cart_bank0_in = cart_bank0;
    assign inner_cart_bank1_in = cart_bank1;
    assign inner_cart_bank2_in = cart_bank2;
    assign inner_cart_bank3_in = cart_bank3;
    assign inner_cart_pin30_in = cart_pin30;
    assign inner_cart_pin31_in = cart_pin31;
    assign cart_bank0 = cart_bank0_dir ? inner_cart_bank0_out : 8'hzz;
    assign cart_bank1 = cart_bank1_dir ? inner_cart_bank1_out : 8'hzz;
    assign cart_bank2 = cart_bank2_dir ? inner_cart_bank2_out : 8'hzz;
    assign cart_bank3 = cart_bank3_dir ? inner_cart_bank3_out : 8'hzz;
    assign cart_pin30 = cart_pin30_dir ? inner_cart_pin30_out : 1'bz;
    assign cart_pin31 = cart_pin31_dir ? inner_cart_pin31_out : 1'bz;

    assign inner_link_so_in = link_so;
    assign inner_link_si_in = link_si;
    assign inner_link_sd_in = link_sd;
    assign inner_link_sc_in = link_sc;
    assign link_so = link_so_dir ? inner_link_so_out : 1'bz;
    assign link_si = link_si_dir ? inner_link_si_out : 1'bz;
    assign link_sd = link_sd_dir ? inner_link_sd_out : 1'bz;
    assign link_sc = link_sc_dir ? inner_link_sc_out : 1'bz;

    assign inner_pmod_in = pmod;
    assign pmod[0] = inner_pmod_dir[0] ? inner_pmod_out[0] : 1'bz;
    assign pmod[1] = inner_pmod_dir[1] ? inner_pmod_out[1] : 1'bz;
    assign pmod[2] = inner_pmod_dir[2] ? inner_pmod_out[2] : 1'bz;
    assign pmod[3] = inner_pmod_dir[3] ? inner_pmod_out[3] : 1'bz;
endmodule


// file: clk_wiz_0.v
//
// (c) Copyright 2008 - 2013 Xilinx, Inc. All rights reserved.
//
// This file contains confidential and proprietary information
// of Xilinx, Inc. and is protected under U.S. and
// international copyright and other intellectual property
// laws.
//
// DISCLAIMER
// This disclaimer is not a license and does not grant any
// rights to the materials distributed herewith. Except as
// otherwise provided in a valid license issued to you by
// Xilinx, and to the maximum extent permitted by applicable
// law: (1) THESE MATERIALS ARE MADE AVAILABLE "AS IS" AND
// WITH ALL FAULTS, AND XILINX HEREBY DISCLAIMS ALL WARRANTIES
// AND CONDITIONS, EXPRESS, IMPLIED, OR STATUTORY, INCLUDING
// BUT NOT LIMITED TO WARRANTIES OF MERCHANTABILITY, NON-
// INFRINGEMENT, OR FITNESS FOR ANY PARTICULAR PURPOSE; and
// (2) Xilinx shall not be liable (whether in contract or tort,
// including negligence, or under any other theory of
// liability) for any loss or damage of any kind or nature
// related to, arising under or in connection with these
// materials, including for any direct, or any indirect,
// special, incidental, or consequential loss or damage
// (including loss of data, profits, goodwill, or any type of
// loss or damage suffered as a result of any action brought
// by a third party) even if such damage or loss was
// reasonably foreseeable or Xilinx had been advised of the
// possibility of the same.
//
// CRITICAL APPLICATIONS
// Xilinx products are not designed or intended to be fail-
// safe, or for use in any application requiring fail-safe
// performance, such as life-support or safety devices or
// systems, Class III medical devices, nuclear facilities,
// applications related to the deployment of airbags, or any
// other applications that could lead to death, personal
// injury, or severe property or environmental damage
// (individually and collectively, "Critical
// Applications"). Customer assumes the sole risk and
// liability of any use of Xilinx products in Critical
// Applications, subject only to applicable laws and
// regulations governing limitations on product liability.
//
// THIS COPYRIGHT NOTICE AND DISCLAIMER MUST BE RETAINED AS
// PART OF THIS FILE AT ALL TIMES.
//
//----------------------------------------------------------------------------
// User entered comments
//----------------------------------------------------------------------------
// None
//
//----------------------------------------------------------------------------
//  Output     Output      Phase    Duty Cycle   Pk-to-Pk     Phase
//   Clock     Freq (MHz)  (degrees)    (%)     Jitter (ps)  Error (ps)
//----------------------------------------------------------------------------
// clk_out_12mhz__12.28741______0.000______50.0______649.540____409.632
//
//----------------------------------------------------------------------------
// Input Clock   Freq (MHz)    Input Jitter (UI)
//----------------------------------------------------------------------------
// __primary__________50.000____________0.010

`timescale 1ps/1ps

module clk_wiz_0_clk_wiz

 (// Clock in ports
  // Clock out ports
  output        clk_out_12mhz,
  // Status and control signals
  input         reset,
  output        locked,
  input         clk_in_50mhz
 );
  // Input buffering
  //------------------------------------
wire clk_in_50mhz_clk_wiz_0;
wire clk_in2_clk_wiz_0;
assign clk_in_50mhz_clk_wiz_0 = clk_in_50mhz;




  // Clocking PRIMITIVE
  //------------------------------------

  // Instantiation of the MMCM PRIMITIVE
  //    * Unused inputs are tied off
  //    * Unused outputs are labeled unused

  wire        clk_out_12mhz_clk_wiz_0;
  wire        clk_out2_clk_wiz_0;
  wire        clk_out3_clk_wiz_0;
  wire        clk_out4_clk_wiz_0;
  wire        clk_out5_clk_wiz_0;
  wire        clk_out6_clk_wiz_0;
  wire        clk_out7_clk_wiz_0;

  wire [15:0] do_unused;
  wire        drdy_unused;
  wire        psdone_unused;
  wire        locked_int;
  wire        clkfbout_clk_wiz_0;
  wire        clkfbout_buf_clk_wiz_0;
  wire        clkfboutb_unused;
    wire clkout0b_unused;
   wire clkout1_unused;
   wire clkout1b_unused;
   wire clkout2_unused;
   wire clkout2b_unused;
   wire clkout3_unused;
   wire clkout3b_unused;
   wire clkout4_unused;
  wire        clkout5_unused;
  wire        clkout6_unused;
  wire        clkfbstopped_unused;
  wire        clkinstopped_unused;
  wire        reset_high;

  MMCME2_ADV
  #(.BANDWIDTH            ("OPTIMIZED"),
    .CLKOUT4_CASCADE      ("FALSE"),
    .COMPENSATION         ("ZHOLD"),
    .STARTUP_WAIT         ("FALSE"),
    .DIVCLK_DIVIDE        (3),
    .CLKFBOUT_MULT_F      (36.125),
    .CLKFBOUT_PHASE       (0.000),
    .CLKFBOUT_USE_FINE_PS ("FALSE"),
    .CLKOUT0_DIVIDE_F     (49.000),
    .CLKOUT0_PHASE        (0.000),
    .CLKOUT0_DUTY_CYCLE   (0.5),
    .CLKOUT0_USE_FINE_PS  ("FALSE"),
    .CLKIN1_PERIOD        (20.000))
  mmcm_adv_inst
    // Output clocks
   (
    .CLKFBOUT            (clkfbout_clk_wiz_0),
    .CLKFBOUTB           (clkfboutb_unused),
    .CLKOUT0             (clk_out_12mhz_clk_wiz_0),
    .CLKOUT0B            (clkout0b_unused),
    .CLKOUT1             (clkout1_unused),
    .CLKOUT1B            (clkout1b_unused),
    .CLKOUT2             (clkout2_unused),
    .CLKOUT2B            (clkout2b_unused),
    .CLKOUT3             (clkout3_unused),
    .CLKOUT3B            (clkout3b_unused),
    .CLKOUT4             (clkout4_unused),
    .CLKOUT5             (clkout5_unused),
    .CLKOUT6             (clkout6_unused),
     // Input clock control
    .CLKFBIN             (clkfbout_buf_clk_wiz_0),
    .CLKIN1              (clk_in_50mhz_clk_wiz_0),
    .CLKIN2              (1'b0),
     // Tied to always select the primary input clock
    .CLKINSEL            (1'b1),
    // Ports for dynamic reconfiguration
    .DADDR               (7'h0),
    .DCLK                (1'b0),
    .DEN                 (1'b0),
    .DI                  (16'h0),
    .DO                  (do_unused),
    .DRDY                (drdy_unused),
    .DWE                 (1'b0),
    // Ports for dynamic phase shift
    .PSCLK               (1'b0),
    .PSEN                (1'b0),
    .PSINCDEC            (1'b0),
    .PSDONE              (psdone_unused),
    // Other control and status signals
    .LOCKED              (locked_int),
    .CLKINSTOPPED        (clkinstopped_unused),
    .CLKFBSTOPPED        (clkfbstopped_unused),
    .PWRDWN              (1'b0),
    .RST                 (reset_high));
  assign reset_high = reset;

  assign locked = locked_int;
// Clock Monitor clock assigning
//--------------------------------------
 // Output buffering
  //-----------------------------------

  BUFG clkf_buf
   (.O (clkfbout_buf_clk_wiz_0),
    .I (clkfbout_clk_wiz_0));






  BUFG clkout1_buf
   (.O   (clk_out_12mhz),
    .I   (clk_out_12mhz_clk_wiz_0));




endmodule

// file: clk_wiz_1.v
//
// (c) Copyright 2008 - 2013 Xilinx, Inc. All rights reserved.
//
// This file contains confidential and proprietary information
// of Xilinx, Inc. and is protected under U.S. and
// international copyright and other intellectual property
// laws.
//
// DISCLAIMER
// This disclaimer is not a license and does not grant any
// rights to the materials distributed herewith. Except as
// otherwise provided in a valid license issued to you by
// Xilinx, and to the maximum extent permitted by applicable
// law: (1) THESE MATERIALS ARE MADE AVAILABLE "AS IS" AND
// WITH ALL FAULTS, AND XILINX HEREBY DISCLAIMS ALL WARRANTIES
// AND CONDITIONS, EXPRESS, IMPLIED, OR STATUTORY, INCLUDING
// BUT NOT LIMITED TO WARRANTIES OF MERCHANTABILITY, NON-
// INFRINGEMENT, OR FITNESS FOR ANY PARTICULAR PURPOSE; and
// (2) Xilinx shall not be liable (whether in contract or tort,
// including negligence, or under any other theory of
// liability) for any loss or damage of any kind or nature
// related to, arising under or in connection with these
// materials, including for any direct, or any indirect,
// special, incidental, or consequential loss or damage
// (including loss of data, profits, goodwill, or any type of
// loss or damage suffered as a result of any action brought
// by a third party) even if such damage or loss was
// reasonably foreseeable or Xilinx had been advised of the
// possibility of the same.
//
// CRITICAL APPLICATIONS
// Xilinx products are not designed or intended to be fail-
// safe, or for use in any application requiring fail-safe
// performance, such as life-support or safety devices or
// systems, Class III medical devices, nuclear facilities,
// applications related to the deployment of airbags, or any
// other applications that could lead to death, personal
// injury, or severe property or environmental damage
// (individually and collectively, "Critical
// Applications"). Customer assumes the sole risk and
// liability of any use of Xilinx products in Critical
// Applications, subject only to applicable laws and
// regulations governing limitations on product liability.
//
// THIS COPYRIGHT NOTICE AND DISCLAIMER MUST BE RETAINED AS
// PART OF THIS FILE AT ALL TIMES.
//
//----------------------------------------------------------------------------
// User entered comments
//----------------------------------------------------------------------------
// None
//
//----------------------------------------------------------------------------
//  Output     Output      Phase    Duty Cycle   Pk-to-Pk     Phase
//   Clock     Freq (MHz)  (degrees)    (%)     Jitter (ps)  Error (ps)
//----------------------------------------------------------------------------
// clk_out_8mhz___8.38821______0.000______50.0______909.008____863.115
//
//----------------------------------------------------------------------------
// Input Clock   Freq (MHz)    Input Jitter (UI)
//----------------------------------------------------------------------------
// __primary__________50.000____________0.010

`timescale 1ps/1ps

module clk_wiz_1_clk_wiz

 (// Clock in ports
  // Clock out ports
  output        clk_out_8mhz,
  // Status and control signals
  input         reset,
  output        locked,
  input         clk_in_50mhz
 );
  // Input buffering
  //------------------------------------
wire clk_in_50mhz_clk_wiz_1;
wire clk_in2_clk_wiz_1;
assign clk_in_50mhz_clk_wiz_1 = clk_in_50mhz;




  // Clocking PRIMITIVE
  //------------------------------------

  // Instantiation of the MMCM PRIMITIVE
  //    * Unused inputs are tied off
  //    * Unused outputs are labeled unused

  wire        clk_out_8mhz_clk_wiz_1;
  wire        clk_out2_clk_wiz_1;
  wire        clk_out3_clk_wiz_1;
  wire        clk_out4_clk_wiz_1;
  wire        clk_out5_clk_wiz_1;
  wire        clk_out6_clk_wiz_1;
  wire        clk_out7_clk_wiz_1;

  wire [15:0] do_unused;
  wire        drdy_unused;
  wire        psdone_unused;
  wire        locked_int;
  wire        clkfbout_clk_wiz_1;
  wire        clkfbout_buf_clk_wiz_1;
  wire        clkfboutb_unused;
    wire clkout0b_unused;
   wire clkout1_unused;
   wire clkout1b_unused;
   wire clkout2_unused;
   wire clkout2b_unused;
   wire clkout3_unused;
   wire clkout3b_unused;
   wire clkout4_unused;
  wire        clkout5_unused;
  wire        clkout6_unused;
  wire        clkfbstopped_unused;
  wire        clkinstopped_unused;
  wire        reset_high;

  MMCME2_ADV
  #(.BANDWIDTH            ("OPTIMIZED"),
    .CLKOUT4_CASCADE      ("FALSE"),
    .COMPENSATION         ("ZHOLD"),
    .STARTUP_WAIT         ("FALSE"),
    .DIVCLK_DIVIDE        (5),
    .CLKFBOUT_MULT_F      (60.500),
    .CLKFBOUT_PHASE       (0.000),
    .CLKFBOUT_USE_FINE_PS ("FALSE"),
    .CLKOUT0_DIVIDE_F     (72.125),
    .CLKOUT0_PHASE        (0.000),
    .CLKOUT0_DUTY_CYCLE   (0.5),
    .CLKOUT0_USE_FINE_PS  ("FALSE"),
    .CLKIN1_PERIOD        (20.000))
  mmcm_adv_inst
    // Output clocks
   (
    .CLKFBOUT            (clkfbout_clk_wiz_1),
    .CLKFBOUTB           (clkfboutb_unused),
    .CLKOUT0             (clk_out_8mhz_clk_wiz_1),
    .CLKOUT0B            (clkout0b_unused),
    .CLKOUT1             (clkout1_unused),
    .CLKOUT1B            (clkout1b_unused),
    .CLKOUT2             (clkout2_unused),
    .CLKOUT2B            (clkout2b_unused),
    .CLKOUT3             (clkout3_unused),
    .CLKOUT3B            (clkout3b_unused),
    .CLKOUT4             (clkout4_unused),
    .CLKOUT5             (clkout5_unused),
    .CLKOUT6             (clkout6_unused),
     // Input clock control
    .CLKFBIN             (clkfbout_buf_clk_wiz_1),
    .CLKIN1              (clk_in_50mhz_clk_wiz_1),
    .CLKIN2              (1'b0),
     // Tied to always select the primary input clock
    .CLKINSEL            (1'b1),
    // Ports for dynamic reconfiguration
    .DADDR               (7'h0),
    .DCLK                (1'b0),
    .DEN                 (1'b0),
    .DI                  (16'h0),
    .DO                  (do_unused),
    .DRDY                (drdy_unused),
    .DWE                 (1'b0),
    // Ports for dynamic phase shift
    .PSCLK               (1'b0),
    .PSEN                (1'b0),
    .PSINCDEC            (1'b0),
    .PSDONE              (psdone_unused),
    // Other control and status signals
    .LOCKED              (locked_int),
    .CLKINSTOPPED        (clkinstopped_unused),
    .CLKFBSTOPPED        (clkfbstopped_unused),
    .PWRDWN              (1'b0),
    .RST                 (reset_high));
  assign reset_high = reset;

  assign locked = locked_int;
// Clock Monitor clock assigning
//--------------------------------------
 // Output buffering
  //-----------------------------------

  BUFG clkf_buf
   (.O (clkfbout_buf_clk_wiz_1),
    .I (clkfbout_clk_wiz_1));






  BUFG clkout1_buf
   (.O   (clk_out_8mhz),
    .I   (clk_out_8mhz_clk_wiz_1));




endmodule
