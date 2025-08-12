use anyhow::Context;
use flate2::read::GzDecoder;

use device::Device;

use crate::{device::drivers::fpga, ui::UI};

mod bitstream;
mod cart_backup;
mod cli;
mod device;
mod hwinfo;
mod input;
mod kvs;
mod led;
mod power;
pub mod ui;
mod util;
mod worker;

fn main() -> anyhow::Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();
    esp_idf_svc::log::set_target_level("gpio", log::LevelFilter::Warn).unwrap();

    kvs::Kvs::init()?;
    log::info!("Hardware version: {}", hwinfo::get_hardware_version());
    log::info!("Serial: {}", hwinfo::get_serial_number());

    // Check that the firmware is compatible with the listed revision.
    cfg_if::cfg_if! {
        if #[cfg(feature = "rev1")] {
            let required_revision = 1;
        } else if #[cfg(feature = "rev2")] {
            let required_revision = 2;
        } else if #[cfg(feature = "rev3")] {
            let required_revision = 3;
        } else {
            compile_error!("No board revision selected");
        }
    };
    let actual_revision = hwinfo::get_hardware_version().major;
    if actual_revision != required_revision && actual_revision != 0 {
        anyhow::bail!(
            "Incompatible firmware revision: device={:?} firmware={}",
            actual_revision,
            required_revision
        );
    }

    // Proceed to initialize device.
    Device::init()?;
    let mut device = Device::lock();
    if device.sdcard.is_none() {
        log::warn!("Failed to mount SD card");
    }
    device.set_brightness(kvs::keys::BRIGHTNESS.get().unwrap());

    // Setup workers.
    worker::start();
    cli::start();
    power::PowerManager::start(&mut device);

    // Initial programming FPGA
    fn program_fpga(device: &mut Device) -> anyhow::Result<()> {
        let bitstream =
            util::open_system_file("boot.bit.gz").context("Failed to read bitstream")?;
        let mut bitstream = GzDecoder::new(bitstream);
        device
            .fpga
            .program(&mut bitstream)
            .context("Failed to program FPGA")?;
        device.fpga.enable_interrupt(fpga::Irq::Button)?;
        device.lcd.enable_fpga_control()?;
        Ok(())
    }
    program_fpga(&mut device).expect("Failed to program FPGA");

    // Setup UI
    let mut ui = UI::new(&mut device);
    std::mem::drop(device);

    // Run UI in this thread.
    led::LedController::set_behavior(led::LedBehavior::OFF);
    ui.run();
}
