module top_handheld (
    input clk_50mhz,

    input mcu_spi_clk,
    input mcu_spi_cs,
    inout mcu_spi_pico,
    inout mcu_spi_poci,
    inout mcu_spi_d2,
    inout mcu_spi_d3,

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
    output [7:0] cart_bank1,
    output [7:0] cart_bank2,
    output [3:0] cart_bank3,
    output cart_pin30,
    output cart_pin30_dir,
    output cart_pin31,
    output cart_pin31_dir,
    output cart_bank0_dir,
    output cart_bank1_dir,
    output cart_bank2_dir,
    output cart_bank3_dir,
    input  cart_switch,
    output  cart_en_3v3,
    output  cart_en_5v0,
    output  cart_oe_n,

    output [3:0] pmod,
    output vibrate_en
);
    logic pll_reset = 1'd0;
    logic reset;
    assign reset = ~btn_y;

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

    logic [1:0] gb_tCycle;
    logic [7:0] gb_dataRead;
    logic [7:0] gb_dataWrite;
    logic [15:0] gb_cart_address;
    logic gb_cart_enable;
    logic gb_cart_write;
    logic gb_cart_chipSelect;

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

        .io_cartridge_dataRead(gb_dataRead),
        .io_cartridge_dataWrite(gb_dataWrite),
        .io_cartridge_address(gb_cart_address),
        .io_cartridge_enable(gb_cart_enable),
        .io_cartridge_write(gb_cart_write),
        .io_cartridge_chipSelect(gb_cart_chipSelect),
        .io_tCycle(gb_tCycle),

        // .io_serial_out(),
        .io_serial_in(1'd0),
        // .io_serial_clockEnable(),
        // .io_serial_clockOut(),
        .io_serial_clockIn(1'd0),

        .io_pmod(pmod)
        // NOTE: no comma at the end
    );

    /////////////////////////////////////////////////
    // Physical Cartridge I/O
    /////////////////////////////////////////////////
    // Direction: high is output, low is input
    // bank0: data
    // bank1: address8-15
    // bank2: address0-7
    // bank3: 0: nCS1, 1: nRD, 2: nWR, 3: PHI
    // Pin 30: nRST (GB) / nCS2 (GBA)
    // Pin 31: VIN (GB) / nIRQ (GBA)
    assign cart_oe_n = 1'b0; // Enabled if physical cartridge in use (active low).
    assign cart_bank3_dir = 1'b1; // Output
    assign cart_bank1_dir = 1'b1; // Output
    assign cart_bank2_dir = 1'b1; // Output
    assign cart_bank0_dir = ~cart_bank3[2]; // Output if writing.
    assign cart_pin30_dir = 1'b1; // Output
    assign cart_pin31_dir = 1'b0; // Input

    assign gb_dataRead = cart_bank0;
    assign cart_bank0 = (gb_cart_enable && gb_cart_write) ? gb_dataWrite : 8'hzz;
    assign cart_bank2 = gb_cart_address[7:0];
    assign cart_bank1 = gb_cart_address[15:8];
    // TODO: see how this interacts with HDMA in regular speed mode.
    // Probably doesn't matter, because even though HDMA is faster, it *never* writes.
    assign cart_bank3[2] = ~(gb_cart_enable && gb_cart_write && (gb_tCycle == 2'd1 || gb_tCycle == 2'd2));
    assign cart_bank3[1] = ~cart_bank3[2];
    assign cart_bank3[0] = gb_cart_chipSelect; // high for ROM low for RAM 
    assign cart_pin30 = 1'd1; // reset
    assign cart_bank3[3] = 1'd0; // phi

    // Cartridge voltages
    assign cart_en_3v3 = ~cart_switch;
    assign cart_en_5v0 = cart_switch;

    // For testing
    assign vibrate_en = ~btn_l;
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
