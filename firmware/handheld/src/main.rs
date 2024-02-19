use std::time::Duration;

use esp_idf_svc::hal::gpio::*;
use esp_idf_svc::hal::peripherals::Peripherals;
use esp_idf_svc::hal::spi::*;
use esp_idf_svc::hal::units::FromValueType;

mod lcd;

fn main() -> anyhow::Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    let peripherals = Peripherals::take()?;
    let mut led = PinDriver::output(peripherals.pins.gpio3)?;

    // Turn LED off.
    led.set_high()?;
    log::info!("Hello, world!!");

    let mut fpga_power =  PinDriver::output(peripherals.pins.gpio46)?;
    fpga_power.set_high()?;

    let lcd_reset = PinDriver::output(peripherals.pins.gpio7)?;
    let lcd_dc = PinDriver::output(peripherals.pins.gpio16)?;
    let mut lcd_backlight = PinDriver::output(peripherals.pins.gpio6)?;
    let lcd_cs = peripherals.pins.gpio15;
    let spi_clk = peripherals.pins.gpio12;
    let spi_sdo = peripherals.pins.gpio11;
    let spi_sdi = peripherals.pins.gpio13;

    let lcd_spi_config = config::Config::new().baudrate(10.MHz().into());
    let lcd_spi = SpiDeviceDriver::new_single(
        peripherals.spi2,
        spi_clk,
        spi_sdo,
        Some(spi_sdi),
        Some(lcd_cs),
        &SpiDriverConfig::new(),
        &lcd_spi_config,
    )?;

    // Setup LCD
    log::info!("Initializing LCD");
    lcd_backlight.set_high()?;
    let mut lcd = lcd::ILI9488::new(lcd_reset, lcd_dc, lcd_spi);
    lcd.init()?;
    log::info!("Done initializing LCD");

    loop {
        std::thread::sleep(Duration::from_secs(1));
    }
}
