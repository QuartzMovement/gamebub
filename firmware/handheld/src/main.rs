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

    // Initial programming FPGA
    fn program_fpga(device: &mut Device) -> anyhow::Result<()> {
        let bitstream =
            File::open("/sdcard/system/base.bit.gz").context("Failed to read bitstream")?;
        let mut bitstream = GzDecoder::new(bitstream);
        device
            .fpga
            .program(&mut bitstream)
            .context("Failed to program FPGA")?;
        device.lcd.enable_fpga_control()?;
        Ok(())
    }
    let fpga_program_result = program_fpga(&mut device);

    // Setup UI
    let mut ui = UI::new(&mut device);
    std::mem::drop(device);

    // If there was an error programming the FPGA, show it now.
    if let Err(error) = fpga_program_result {
        show_fatal_error_anyhow(error);
    }

    // Run UI in this thread.
    ui.run();
}

fn show_fatal_error_anyhow(error: anyhow::Error) {
    use std::fmt::Write;

    let mut message = format!("{}\n", error);
    for e in error.chain().skip(1) {
        let _ = write!(message, "\n{}", e);
    }
    show_fatal_error(message);
}

fn show_fatal_error(error: String) {
    Device::lock().lcd.enable_mcu_control().unwrap();
    ui::send(ui::Message::FatalError(error));
    ui::send(ui::Message::Redraw);
}
