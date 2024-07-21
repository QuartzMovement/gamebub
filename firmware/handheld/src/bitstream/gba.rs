use std::{fs::File, io::Read, path::Path};

use thiserror::Error;

use crate::device::{drivers::fpga, Device};

use super::Bitstream;

const REG_EMU_CART_CONFIG: u32 = 0xC000_0000;

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

    fn load_bios(&mut self, device: &mut Device) -> Result<(), GbaError> {
        let mut bios_file = File::open("/sdcard/system/gba.bios.bin")?;
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
        device.fpga.write_u32(REG_EMU_CART_CONFIG, 0)?;

        // Disable IRQs (including vblank)
        device.fpga.write_u32(fpga::REG_IRQ_ENABLE, 0)?;

        // Resume
        device.fpga.write_u32(fpga::REG_CONTROL, 0b1011)?;
        device.imu.disable_accel().unwrap();

        Ok(())
    }

    pub fn set_emulated_cartridge(&mut self, rom_path: &Path) -> Result<(), GbaError> {
        let mut device = Device::lock();

        // Hold in reset
        device.fpga.write_u32(fpga::REG_CONTROL, 0b0000)?;

        // Load bios
        // TODO shouldn't need to keep doing this, just do it once the bitstream is loaded.
        self.load_bios(&mut device)?;

        // Load ROM
        let mut rom_file = File::open(rom_path)?;
        log::info!("Loading rom");
        // TODO do auto-detection of backup format

        const CHUNK_SIZE: usize = 16 * 1024;
        let mut buf = vec![0; CHUNK_SIZE].into_boxed_slice();
        let mut total = 0u32;
        loop {
            let n = rom_file.read(&mut buf)?;
            if n == 0 {
                break;
            }
            device.fpga.sdram_write(total, &buf[..n])?;
            total += n as u32;
        }
        // TODO clear up to the next power of two

        // TODO load backup (save) file

        // Configure emulated cartridge control registers
        let emu_cart_config = 1u32; // enabled, no backup
        device
            .fpga
            .write_u32(REG_EMU_CART_CONFIG, emu_cart_config)?;

        // Disable IRQs (including vblank)
        device.fpga.write_u32(fpga::REG_IRQ_ENABLE, 0)?;

        // Resume
        device.fpga.write_u32(fpga::REG_CONTROL, 0b1011)?;

        Ok(())
    }
}

impl Bitstream for Gba {
    fn get_bitstream_path(&self) -> &'static str {
        return "/sdcard/system/gba.bit.gz";
    }

    fn on_after_program(&mut self) -> Result<(), String> {
        let mut device = Device::lock();
        self.load_bios(&mut device).map_err(|e| e.to_string())
    }

    fn set_paused(&mut self, paused: bool) -> Result<(), fpga::Error> {
        let mut device = Device::lock();

        device
            .fpga
            .write_u32(fpga::REG_CONTROL, 0b1010u32 | ((!paused) as u32))
    }

    fn reset(&mut self) -> Result<(), fpga::Error> {
        let mut device = Device::lock();
        device.fpga.write_u32(fpga::REG_CONTROL, 0b0000)?;
        device.fpga.write_u32(fpga::REG_CONTROL, 0b1010)?;
        Ok(())
    }
}
