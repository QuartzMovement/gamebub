use esp_idf_svc::hal::gpio::*;
use esp_idf_svc::hal::peripherals::Peripherals;
use std::time::Duration;

fn main() -> anyhow::Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    let peripherals = Peripherals::take()?;
    let mut led = PinDriver::output(peripherals.pins.gpio3)?;

    log::info!("Hello, world!");

    loop {
        led.set_high()?;
        std::thread::sleep(Duration::from_millis(500));
        led.set_low()?;
        std::thread::sleep(Duration::from_millis(500));
    }
}
