use std::time::Duration;

use esp_idf_svc::hal::gpio::*;
use esp_idf_svc::hal::ledc::{self, *};
use esp_idf_svc::hal::peripherals::Peripherals;
use esp_idf_svc::hal::spi::{self, *};
use esp_idf_svc::hal::units::FromValueType;

mod lcd;
mod sdcard;

fn main() -> anyhow::Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    let peripherals = Peripherals::take()?;
    let mut led = PinDriver::output(peripherals.pins.gpio3)?;

    // Turn LED off.
    led.set_high()?;
    log::info!("Hello, world!!");

    let mut fpga_power = PinDriver::output(peripherals.pins.gpio46)?;
    fpga_power.set_high()?;

    let lcd_reset = PinDriver::output(peripherals.pins.gpio7)?;
    let lcd_dc = PinDriver::output(peripherals.pins.gpio16)?;
    let lcd_backlight =peripherals.pins.gpio6;
    let lcd_cs = peripherals.pins.gpio15;
    let spi_clk = peripherals.pins.gpio12;
    let spi_sdo = peripherals.pins.gpio11;
    let spi_sdi = peripherals.pins.gpio13;

    let lcd_spi_config = spi::config::Config::new().baudrate(10.MHz().into());
    let lcd_spi = SpiDeviceDriver::new_single(
        peripherals.spi2,
        spi_clk,
        spi_sdo,
        Some(spi_sdi),
        Some(lcd_cs),
        &SpiDriverConfig::new(),
        &lcd_spi_config,
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
    let mut lcd = lcd::ILI9488::new(lcd_reset, lcd_dc, lcd_spi);
    lcd.init()?;
    log::info!("Done initializing LCD");

    // Mount sdcard to /sdcard
    sdcard::mount_sdcard()?;
    let paths = std::fs::read_dir("/sdcard").unwrap();
    for path in paths {
        println!("sdcard: {}", path.unwrap().path().display())
    }

    loop {
        std::thread::sleep(Duration::from_secs(1));
    }
}
