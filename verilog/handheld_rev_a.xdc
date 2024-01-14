## Enable bitstream compression
set_property BITSTREAM.GENERAL.COMPRESS True [current_design]

## Clock signal
set_property -dict { PACKAGE_PIN E3    IOSTANDARD LVCMOS33 } [get_ports { clk_50mhz }];
create_clock -add -name sys_clk_pin -period 20.00 -waveform {0 10} [get_ports { clk_50mhz }];

## SPI
set_property -dict { PACKAGE_PIN F4    IOSTANDARD LVCMOS33 } [get_ports { spi_clk  }];
set_property -dict { PACKAGE_PIN J2    IOSTANDARD LVCMOS33 } [get_ports { spi_cs   }];
set_property -dict { PACKAGE_PIN H2    IOSTANDARD LVCMOS33 } [get_ports { spi_pico }];
set_property -dict { PACKAGE_PIN G1    IOSTANDARD LVCMOS33 } [get_ports { spi_poci }];
set_property -dict { PACKAGE_PIN G2    IOSTANDARD LVCMOS33 } [get_ports { spi_d2   }];
set_property -dict { PACKAGE_PIN H1    IOSTANDARD LVCMOS33 } [get_ports { spi_d3   }];

## Buttons
set_property -dict { PACKAGE_PIN G6    IOSTANDARD LVCMOS33 } [get_ports { btn_left   }];
set_property -dict { PACKAGE_PIN F5    IOSTANDARD LVCMOS33 } [get_ports { btn_right  }];
set_property -dict { PACKAGE_PIN E5    IOSTANDARD LVCMOS33 } [get_ports { btn_up     }];
set_property -dict { PACKAGE_PIN H5    IOSTANDARD LVCMOS33 } [get_ports { btn_down   }];
set_property -dict { PACKAGE_PIN N5    IOSTANDARD LVCMOS33 } [get_ports { btn_a      }];
set_property -dict { PACKAGE_PIN P5    IOSTANDARD LVCMOS33 } [get_ports { btn_b      }];
set_property -dict { PACKAGE_PIN L6    IOSTANDARD LVCMOS33 } [get_ports { btn_x      }];
set_property -dict { PACKAGE_PIN M6    IOSTANDARD LVCMOS33 } [get_ports { btn_y      }];
set_property -dict { PACKAGE_PIN K5    IOSTANDARD LVCMOS33 } [get_ports { btn_start  }];
set_property -dict { PACKAGE_PIN J5    IOSTANDARD LVCMOS33 } [get_ports { btn_select }];
set_property -dict { PACKAGE_PIN H6    IOSTANDARD LVCMOS33 } [get_ports { btn_l      }];
set_property -dict { PACKAGE_PIN L5    IOSTANDARD LVCMOS33 } [get_ports { btn_r      }];

## PMOD
set_property -dict { PACKAGE_PIN M14    IOSTANDARD LVCMOS33 } [get_ports { pmod[0] }];
set_property -dict { PACKAGE_PIN N14    IOSTANDARD LVCMOS33 } [get_ports { pmod[1] }];
set_property -dict { PACKAGE_PIN P14    IOSTANDARD LVCMOS33 } [get_ports { pmod[2] }];
set_property -dict { PACKAGE_PIN M13    IOSTANDARD LVCMOS33 } [get_ports { pmod[3] }];

## I2S
set_property -dict { PACKAGE_PIN G4    IOSTANDARD LVCMOS33 } [get_ports { i2s_mclk }];
set_property -dict { PACKAGE_PIN G3    IOSTANDARD LVCMOS33 } [get_ports { i2s_bclk }];
set_property -dict { PACKAGE_PIN H4    IOSTANDARD LVCMOS33 } [get_ports { i2s_wclk }];
set_property -dict { PACKAGE_PIN J4    IOSTANDARD LVCMOS33 } [get_ports { i2s_din  }];

## LCD
set_property -dict { PACKAGE_PIN E17    IOSTANDARD LVCMOS33 } [get_ports { lcd_dotclk  }];
set_property -dict { PACKAGE_PIN E18    IOSTANDARD LVCMOS33 } [get_ports { lcd_hsync   }];
set_property -dict { PACKAGE_PIN D17    IOSTANDARD LVCMOS33 } [get_ports { lcd_vsync   }];
set_property -dict { PACKAGE_PIN F18    IOSTANDARD LVCMOS33 } [get_ports { lcd_data_en }];

set_property -dict { PACKAGE_PIN K16    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[0]  }];
set_property -dict { PACKAGE_PIN K15    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[1]  }];
set_property -dict { PACKAGE_PIN J15    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[2]  }];
set_property -dict { PACKAGE_PIN H15    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[3]  }];
set_property -dict { PACKAGE_PIN G16    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[4]  }];
set_property -dict { PACKAGE_PIN F16    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[5]  }];
set_property -dict { PACKAGE_PIN F15    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[6]  }];
set_property -dict { PACKAGE_PIN E16    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[7]  }];
set_property -dict { PACKAGE_PIN E15    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[8]  }];
set_property -dict { PACKAGE_PIN D15    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[9]  }];
set_property -dict { PACKAGE_PIN L18    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[10] }];
set_property -dict { PACKAGE_PIN K17    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[11] }];
set_property -dict { PACKAGE_PIN J17    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[12] }];
set_property -dict { PACKAGE_PIN J18    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[13] }];
set_property -dict { PACKAGE_PIN H17    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[14] }];
set_property -dict { PACKAGE_PIN H16    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[15] }];
set_property -dict { PACKAGE_PIN G18    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[16] }];
set_property -dict { PACKAGE_PIN G17    IOSTANDARD LVCMOS33 } [get_ports { lcd_db[17] }];
