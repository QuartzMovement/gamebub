use std::{
    fmt::{Debug, Display},
    fs::File,
    io::{Read, Seek},
    path::Path,
    time::{Duration, Instant},
};

use thiserror::Error;

use crate::device::{drivers::fpga, Device};

use super::Bitstream;

const ROM_HEADER_LENGTH: usize = 192;
const REG_EMU_CART_CONFIG: u32 = 0xC000_0000;

#[derive(Debug, Error)]
pub enum GbaError {
    #[error("I/O error")]
    IoError(#[from] std::io::Error),
    #[error("FPGA error")]
    FpgaError(#[from] crate::device::drivers::fpga::Error),
}

#[allow(unused)]
#[derive(Copy, Clone, Debug, PartialEq, Eq, Default)]
enum SaveType {
    /// No backup
    #[default]
    None,
    /// EEPROM - Autodetect Size
    EepromAuto,
    /// EEPROM, 512B
    Eeprom512,
    /// EEPROM, 8KiB
    Eeprom8K,
    /// SRAM or FRAM, 32 KiB
    Sram,
    /// Flash 64KiB
    Flash64K,
    /// Flash 128KiB
    Flash128K,
}

#[derive(Debug, Clone, Default)]
pub struct RomHeader {
    game_title: [u8; 12],
    game_code: [u8; 4],
}

impl RomHeader {
    fn parse(header: [u8; ROM_HEADER_LENGTH]) -> RomHeader {
        RomHeader {
            game_title: header[0xA0..0xAC].try_into().unwrap(),
            game_code: header[0xAC..0xB0].try_into().unwrap(),
        }
    }
}

impl Display for RomHeader {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let title = String::from_utf8_lossy(&self.game_title);
        let code = String::from_utf8_lossy(&self.game_code);
        write!(f, "\'{}\' ({})", title, code)
    }
}

/// Helper struct to auto-detect save file types from a ROM file (streaming)
struct SaveTypeDetector {
    detected: Option<SaveType>,
    buffer: Vec<u8>,
}

impl SaveTypeDetector {
    const OVERLAP: usize = 16;
    const STEP: usize = 4;

    pub fn new() -> Self {
        SaveTypeDetector {
            detected: None,
            buffer: vec![],
        }
    }

    fn get(&self) -> SaveType {
        self.detected.unwrap_or_default()
    }

    fn search(data: &[u8]) -> Option<SaveType> {
        static PATTERNS: &[(&[u8], SaveType)] = &[
            (b"EEPROM_V", SaveType::EepromAuto),
            (b"SRAM_V", SaveType::Sram),
            (b"SRAM_F_V", SaveType::Sram),
            (b"FLASH_V", SaveType::Flash64K),
            (b"FLASH512_V", SaveType::Flash64K),
            (b"FLASH1M_V", SaveType::Flash128K),
        ];
        for start in (0..data.len()).step_by(Self::STEP) {
            let region = &data[start..];
            for &(pattern, type_) in PATTERNS {
                if region.starts_with(pattern) {
                    return Some(type_);
                }
            }
        }
        None
    }

    /// Process the next chunk of data.
    pub fn process(&mut self, data: &[u8]) {
        if self.detected.is_some() {
            return;
        }

        // Check the overlap of the last buffer to this buffer.
        let prefix = &data[..(data.len().min(Self::OVERLAP))];
        self.buffer.extend_from_slice(prefix);
        self.detected = Self::search(prefix);
        if self.detected.is_some() {
            return;
        }

        self.detected = Self::search(data);
        let suffix = &data[(data.len().saturating_sub(Self::OVERLAP) & !(Self::STEP - 1))..];
        self.buffer.clear();
        self.buffer.extend_from_slice(suffix);
    }
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
        let mut rom_header = [0u8; ROM_HEADER_LENGTH];
        rom_file.read(&mut rom_header)?;
        rom_file.seek(std::io::SeekFrom::Start(0))?;
        let rom_header = RomHeader::parse(rom_header);
        log::info!("Loading rom: {}", rom_header);
        let mut save_type_detector = SaveTypeDetector::new();

        const CHUNK_SIZE: usize = 16 * 1024;
        let mut buf = vec![0; CHUNK_SIZE].into_boxed_slice();
        let mut total = 0u32;
        let start_time = Instant::now();
        let mut read_duration = Duration::ZERO;
        let mut transfer_duration = Duration::ZERO;
        let mut detect_duration = Duration::ZERO;
        loop {
            let read_start = Instant::now();
            let n = rom_file.read(&mut buf)?;
            read_duration += read_start.elapsed();
            if n == 0 {
                break;
            }

            let transfer_start = Instant::now();
            device.fpga.sdram_write(total, &buf[..n])?;
            total += n as u32;
            transfer_duration += transfer_start.elapsed();

            let detect_start = Instant::now();
            save_type_detector.process(&buf[..n]);
            detect_duration += detect_start.elapsed();
        }
        let duration = start_time.elapsed();
        log::info!(
            "Loaded ROM: {} bytes in {} ms ({}/{}/{} ms read/transfer/detect)",
            total,
            duration.as_millis(),
            read_duration.as_millis(),
            transfer_duration.as_millis(),
            detect_duration.as_millis(),
        );
        // TODO clear up to the next power of two

        let save_type = save_type_detector.get();
        log::info!("Detected save type: {:?}", save_type);

        // TODO load backup (save) file

        // Configure emulated cartridge control registers
        let emu_cart_config = match save_type {
            SaveType::None => 0b00001,
            SaveType::Sram => 0b00011,
            SaveType::Flash64K => 0b00101,
            SaveType::Flash128K => 0b01101,
            SaveType::EepromAuto => 0b10111,
            SaveType::Eeprom512 => 0b00111,
            SaveType::Eeprom8K => 0b01111,
        };
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
