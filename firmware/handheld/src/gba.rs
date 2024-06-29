use std::{fs::File, io::Read};

use thiserror::Error;

use crate::device::{drivers::fpga, Device};

#[derive(Debug, Error)]
pub enum GbaError {
    #[error("I/O error")]
    IoError(#[from] std::io::Error),
    #[error("FPGA error")]
    FpgaError(#[from] crate::device::drivers::fpga::Error),
}

/// Driver for GBA FPGA module
pub struct Gba {}

impl Gba {
    pub fn new() -> Self {
        Gba {}
    }

    pub fn set_paused(&mut self, paused: bool) -> Result<(), GbaError> {
        let mut device = Device::lock();

        device
            .fpga
            .write_u32(fpga::REG_CONTROL, 0b1010u32 | ((!paused) as u32))?;
        Ok(())
    }

    /// Resets, leaving in a paused state.
    pub fn reset(&mut self) -> Result<(), GbaError> {
        let mut device = Device::lock();
        device.fpga.write_u32(fpga::REG_CONTROL, 0b0000)?;
        device.fpga.write_u32(fpga::REG_CONTROL, 0b1010)?;
        Ok(())
    }

    fn load_bios(&mut self, device: &mut Device) -> Result<(), GbaError> {
        let mut bios_file = File::open("/sdcard/gba-bios.bin")?;
        let mut buf = vec![0u8; 16 * 1024].into_boxed_slice();
        bios_file.read(&mut buf)?;

        let address = 0xC010_0000;
        let command = fpga::SpiCommand {
            word_size: fpga::FpgaSpiWordSize::Bits32,
            byte_swap: true,
            increment_address: true,
        };
        device.fpga.spi_write(command, address, &buf)?;

        Ok(())
    }

    pub fn set_physical_cartridge(&mut self) -> Result<(), GbaError> {
        let mut device = Device::lock();

        // Hold in reset
        device.fpga.write_u32(fpga::REG_CONTROL, 0b0000)?;

        // Load bios
        self.load_bios(&mut device)?;

        // Switch to physical cartridge.
        // TODO: when emulated cartridge is used

        // Disable IRQs (including vblank)
        device.fpga.write_u32(fpga::REG_IRQ_ENABLE, 0)?;

        // Resume
        device.fpga.write_u32(fpga::REG_CONTROL, 0b1011)?;
        device.imu.disable_accel().unwrap();

        Ok(())
    }
}
