`default_nettype none

module top_handheld (
    input  wire clk_50mhz,

    input  wire        mcu_spi_clk,
    input  wire        mcu_spi_cs_n,
    inout  wire [3:0]  mcu_spi_d,
    output wire        mcu_irq_n,

    input  wire        btn_a,
    input  wire        btn_b,
    input  wire        btn_x,
    input  wire        btn_y,
    input  wire        btn_up,
    input  wire        btn_down,
    input  wire        btn_left,
    input  wire        btn_right,
    input  wire        btn_l,
    input  wire        btn_r,
    input  wire        btn_start,
    input  wire        btn_select,

    output wire        dac_mclk,
    output wire        dac_bclk,
    output wire        dac_wclk,
    output wire        dac_din,

    output wire        lcd_dotclk,
    output wire        lcd_hsync,
    output wire        lcd_vsync,
    output wire        lcd_data_en,
    output wire [17:0] lcd_db,

    inout  wire [7:0]  cart_bank0,
    inout  wire [7:0]  cart_bank1,
    inout  wire [7:0]  cart_bank2,
    inout  wire [3:0]  cart_bank3,
    inout  wire        cart_pin30,
    output wire        cart_pin30_dir,
    inout  wire        cart_pin31,
    output wire        cart_pin31_dir,
    output wire        cart_bank0_dir,
    output wire        cart_bank1_dir,
    output wire        cart_bank2_dir,
    output wire        cart_bank3_dir,
    input  wire        cart_switch,
    output wire        cart_en_3v3,
    output wire        cart_en_5v0,
    output wire        cart_oe_n,

    inout  wire        link_so,
    inout  wire        link_si,
    inout  wire        link_sd,
    inout  wire        link_sc,
    output wire        link_so_dir,
    output wire        link_si_dir,
    output wire        link_sd_dir,
    output wire        link_sc_dir,

    output wire [17:0] sram_a,
    inout  wire [15:0] sram_io,
    output wire        sram_ce_n,
    output wire        sram_we_n,
    output wire        sram_oe_n,
    output wire        sram_ub_n,
    output wire        sram_lb_n,

    output wire        sdram_clk,
    output wire        sdram_cs_n,
    output wire        sdram_cke,
    output wire        sdram_ras_n,
    output wire        sdram_cas_n,
    output wire        sdram_we_n,
    output wire        sdram_ldqm,
    output wire        sdram_udqm,
    output wire [1:0]  sdram_bs,
    output wire [12:0] sdram_a,
    inout  wire [15:0] sdram_dq,

    inout  wire [3:0]  pmod,
    output wire        vibrate_en
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

    logic inner_mcu_irq;
    logic [3:0] inner_mcu_spi_data_in;
    logic [3:0] inner_mcu_spi_data_out;
    logic [3:0] inner_mcu_spi_data_dir;

    logic [15:0] inner_sram_io_in;
    logic [15:0] inner_sram_io_out;
    logic inner_sram_io_dir;

    logic [1:0] inner_sdram_dqm;
    logic [15:0] inner_sdram_dq_in;
    logic [15:0] inner_sdram_dq_out;
    logic inner_sdram_dq_dir;

    HandheldTop handheld_top(
        .clock(clk_8mhz),
        .reset(reset),
        .io_clock_av(clk_12mhz),

        .io_mcuIrq(inner_mcu_irq),
        .io_mcuSpiChipSelect(mcu_spi_cs_n),
        .io_mcuSpiClock(mcu_spi_clk),
        .io_mcuSpiDataIn(inner_mcu_spi_data_in),
        .io_mcuSpiDataOut(inner_mcu_spi_data_out),
        .io_mcuSpiDataDir(inner_mcu_spi_data_dir),

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

        .io_sramA(sram_a),
        .io_sramIoIn(inner_sram_io_in),
        .io_sramIoOut(inner_sram_io_out),
        .io_sramIoDir(inner_sram_io_dir),
        .io_sramCeN(sram_ce_n),
        .io_sramWeN(sram_we_n),
        .io_sramOeN(sram_oe_n),
        .io_sramUbN(sram_ub_n),
        .io_sramLbN(sram_lb_n),

        .io_sdramClock(sdram_clk),
        .io_sdram_cke(sdram_cke),
        .io_sdram_cs(sdram_cs_n),
        .io_sdram_ras(sdram_ras_n),
        .io_sdram_cas(sdram_cas_n),
        .io_sdram_we(sdram_we_n),
        .io_sdram_dqm(inner_sdram_dqm),
        .io_sdram_bank(sdram_bs),
        .io_sdram_address(sdram_a),
        .io_sdram_dataIn(inner_sdram_dq_in),
        .io_sdram_dataOut(inner_sdram_dq_out),
        .io_sdram_dataDir(inner_sdram_dq_dir),

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

    assign mcu_irq_n = inner_mcu_irq ? 1'b0 : 1'bz;
    assign inner_mcu_spi_data_in = mcu_spi_d;
    assign mcu_spi_d[0] = inner_mcu_spi_data_dir[0] ? inner_mcu_spi_data_out[0] : 1'bz;
    assign mcu_spi_d[1] = inner_mcu_spi_data_dir[1] ? inner_mcu_spi_data_out[1] : 1'bz;
    assign mcu_spi_d[2] = inner_mcu_spi_data_dir[2] ? inner_mcu_spi_data_out[2] : 1'bz;
    assign mcu_spi_d[3] = inner_mcu_spi_data_dir[3] ? inner_mcu_spi_data_out[3] : 1'bz;

    assign inner_sram_io_in = sram_io;
    assign sram_io = inner_sram_io_dir ? inner_sram_io_out : 16'hzzzz;

    assign {sdram_udqm, sdram_ldqm} = inner_sdram_dqm;
    assign inner_sdram_dq_in = sdram_dq;
    assign sdram_dq = inner_sdram_dq_dir ? inner_sdram_dq_out : 16'hzzzz;
endmodule

`default_nettype wire

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
