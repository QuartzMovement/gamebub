use std::fs::File;

use anyhow::Context;
use flate2::read::GzDecoder;

use device::Device;

use crate::ui::UI;

mod bitstream;
mod device;
mod kvs;
pub mod ui;
mod util;
mod worker;

fn main() -> anyhow::Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    kvs::Kvs::init()?;

    log::info!("Initializing device");
    Device::init()?;
    let mut device = Device::lock();
    device.set_brightness(kvs::keys::BRIGHTNESS.get().unwrap());

    // Setup workers.
    worker::start();

    // Program FPGA
    // TODO: if this fails, show an error once the UI is initialized
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

fn on_fatal_error_anyhow(error: anyhow::Error) {
    use std::fmt::Write;

    let mut message = format!("{}\n", error);
    for e in error.chain().skip(1) {
        let _ = write!(message, "\n{}", e);
    }
    on_fatal_error(message);
}

fn on_fatal_error(error: String) {
    Device::lock().lcd.enable_mcu_control().unwrap();
    ui::send(ui::Message::FatalError(error));
    ui::send(ui::Message::Redraw);
}
