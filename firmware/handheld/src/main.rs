use std::fs::File;

use flate2::read::GzDecoder;

use device::Device;

use crate::ui::UI;

mod device;
mod gameboy;
pub mod ui;

fn main() -> anyhow::Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    log::info!("Initializing device");
    Device::init()?;
    let mut device = Device::lock();

    let paths = std::fs::read_dir("/sdcard").unwrap();
    for path in paths {
        println!("sdcard: {}", path.unwrap().path().display())
    }

    // Test RTC
    let datetime = device.rtc.read_datetime()?;
    match datetime {
        Some(datetime) => log::info!("Current datetime: {:?}", datetime),
        None => {
            log::info!("No date set, resetting");
            device
                .rtc
                .write_datetime(device::drivers::rtc::Datetime::default())?;
        }
    }

    match device.fuel_gauge.get_battery_level() {
        Ok(level) => log::info!("Battery charge: {:.0}%", level),
        Err(_) => log::info!("Unable to read battery charge"),
    }

    // Program FPGA
    {
        let mut bitstream = GzDecoder::new(File::open("/sdcard/top_handheld_cgb.bit.gz")?);
        device.fpga.program(&mut bitstream)?;
        device.lcd.enable_fpga_control()?;
    }

    // Setup and run UI
    let mut ui = UI::new(&mut device);
    std::mem::drop(device);
    ui.run();
}
