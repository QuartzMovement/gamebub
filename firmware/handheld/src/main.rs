use std::fs::File;

use flate2::read::GzDecoder;

use device::Device;

use crate::ui::UI;

mod bitstream;
mod device;
mod kvs;
pub mod ui;
mod worker;

fn main() -> anyhow::Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    kvs::Kvs::init()?;

    log::info!("Initializing device");
    Device::init()?;
    let mut device = Device::lock();

    // Setup workers.
    worker::start();

    // Program FPGA
    {
        let mut bitstream = GzDecoder::new(File::open("/sdcard/system/base.bit.gz")?);
        device.fpga.program(&mut bitstream)?;
        device.lcd.enable_fpga_control()?;
    }

    // Setup and run UI
    let mut ui = UI::new(&mut device);
    std::mem::drop(device);
    ui.run();
}
