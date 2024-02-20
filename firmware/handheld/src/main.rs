use std::fs::File;
use std::time::Duration;

use esp_idf_svc::hal::gpio::*;
use esp_idf_svc::hal::ledc::{self, *};
use esp_idf_svc::hal::peripherals::Peripherals;
use esp_idf_svc::hal::spi::{self, *};
use esp_idf_svc::hal::units::FromValueType;

mod fpga;
mod lcd;
mod sdcard;

fn main() -> anyhow::Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    let peripherals = Peripherals::take()?;
    let mut led = PinDriver::output(peripherals.pins.gpio3)?;

    // Turn LED off.
    led.set_low()?;
    log::info!("Booting handheld");

    let mut fpga_power = PinDriver::output(peripherals.pins.gpio46)?;
    fpga_power.set_high()?;

    let lcd_reset = PinDriver::output(peripherals.pins.gpio7)?;
    let lcd_dc = PinDriver::output(peripherals.pins.gpio16)?;
    let lcd_backlight = peripherals.pins.gpio6;
    let lcd_cs = peripherals.pins.gpio15;
    let spi_clk = peripherals.pins.gpio12;
    let spi_sdo = peripherals.pins.gpio11;
    let spi_sdi = peripherals.pins.gpio13;

    let spi_driver = SpiDriver::new(
        peripherals.spi2,
        spi_clk,
        spi_sdo,
        Some(spi_sdi),
        &SpiDriverConfig::new(),
    )?;

    let mut ledc_channel = LedcDriver::new(
        peripherals.ledc.channel0,
        LedcTimerDriver::new(
            peripherals.ledc.timer0,
            &ledc::config::TimerConfig::new().frequency(30.kHz().into()),
        )?,
        lcd_backlight,
    )?;
    ledc_channel.set_duty(ledc_channel.get_max_duty() / 4)?;

    // Setup LCD
    log::info!("Initializing LCD");
    let lcd_spi_config = spi::config::Config::new().baudrate(10.MHz().into());
    let lcd_spi_shared = Box::leak(Box::new(SpiSharedDeviceDriver::new(
        &spi_driver,
        &lcd_spi_config,
    )?));
    let lcd_spi = SpiSoftCsDeviceDriver::new(lcd_spi_shared, lcd_cs, Level::High)?;
    let mut lcd = lcd::ILI9488::new(lcd_reset, lcd_dc, lcd_spi);
    lcd.init()?;
    log::info!("Done initializing LCD");

    // Mount sdcard to /sdcard
    sdcard::mount_sdcard()?;
    let paths = std::fs::read_dir("/sdcard").unwrap();
    for path in paths {
        println!("sdcard: {}", path.unwrap().path().display())
    }

    // Program FPGA
    let fpga_pin_done = PinDriver::input(peripherals.pins.gpio17)?;
    let fpga_pin_program_b = PinDriver::output_od(peripherals.pins.gpio18)?;
    let fpga_pin_init_b = PinDriver::input(peripherals.pins.gpio8)?;
    let fpga_pin_spi_cs = peripherals.pins.gpio10;
    let fpga_spi_config = spi::config::Config::new().baudrate(20.MHz().into());
    let fpga_spi_shared = Box::leak(Box::new(SpiSharedDeviceDriver::new(
        &spi_driver,
        &fpga_spi_config,
    )?));
    let fpga_spi = SpiSoftCsDeviceDriver::new(fpga_spi_shared, fpga_pin_spi_cs, Level::High)?;
    let mut fpga = fpga::Fpga::new(
        fpga_power,
        fpga_pin_done,
        fpga_pin_program_b,
        fpga_pin_init_b,
        fpga_spi,
    );

    let fpga_program_config = spi::config::Config::new().baudrate(80.MHz().into());
    let fpga_program_driver = SpiSharedDeviceDriver::new(&spi_driver, &fpga_program_config)?;
    let mut bitstream = File::open("/sdcard/top_handheld_cgb.bit")?;
    fpga.program(&fpga_program_driver, &mut bitstream)?;

    fpga.write_u32(0x0000_0000, 0b11)?;

    log::info!("Done, sleeping in a loop.");
    loop {
        std::thread::sleep(Duration::from_secs(1));
    }
}
