use std::{
    fs::File,
    io::{Read, Seek, Write},
    path::{Path, PathBuf},
};
use thiserror::Error;

use crate::device::Device;

const REG_CONTROL: u32 = 0x0000_0000;
const REG_EMU_CART_CONFIG: u32 = 0xC000_0000;
const REG_EMU_CART_ROM_ADDR: u32 = 0xC000_0004;
const REG_EMU_CART_ROM_MASK: u32 = 0xC000_0008;
const REG_EMU_CART_RAM_ADDR: u32 = 0xC000_000C;
const REG_EMU_CART_RAM_MASK: u32 = 0xC000_0010;

#[derive(Debug, Error)]
pub enum GameboyError {
    #[error("unsupported cartridge type {0}")]
    UnsupportedCartridgeType(u8),
    #[error("I/O error")]
    IoError(#[from] std::io::Error),
    #[error("FPGA error")]
    FpgaError(#[from] crate::device::drivers::fpga::Error),
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
enum MbcType {
    None = 0,
    Mbc1 = 1,
    Mbc2 = 2,
    Mbc3 = 3,
    Mbc5 = 4,
}

#[derive(Debug, Clone)]
#[allow(unused)]
pub struct RomHeader {
    mbc: MbcType,
    rom_size: u32,
    ram_size: u32,

    has_ram: bool,
    has_battery: bool,
    has_rtc: bool,
    has_rumble: bool,
    has_sensor: bool,
}

impl RomHeader {
    fn parse(header: [u8; 0x150]) -> Result<RomHeader, GameboyError> {
        macro_rules! cart_type {
            ($mbc:expr, $($field:ident),*) => {
                {
                    $(
                        $field = true;
                    )*
                    $mbc
                }
            }
        }

        let cartridge_type = header[0x147];
        let mut has_ram = false;
        let mut has_battery = false;
        let mut has_rtc = false;
        let mut has_rumble = false;
        let has_sensor = false;
        let mbc = match cartridge_type {
            0x00 => cart_type!(MbcType::None,),
            0x01 => cart_type!(MbcType::Mbc1,),
            0x02 => cart_type!(MbcType::Mbc1, has_ram),
            0x03 => cart_type!(MbcType::Mbc1, has_ram, has_battery),
            0x05 => cart_type!(MbcType::Mbc2, has_ram),
            0x06 => cart_type!(MbcType::Mbc2, has_ram, has_battery),
            0x08 => cart_type!(MbcType::None, has_ram),
            0x09 => cart_type!(MbcType::None, has_ram, has_battery),
            0x0F => cart_type!(MbcType::Mbc3, has_rtc, has_battery),
            0x10 => cart_type!(MbcType::Mbc3, has_rtc, has_ram, has_battery),
            0x11 => cart_type!(MbcType::Mbc3,),
            0x12 => cart_type!(MbcType::Mbc3, has_ram),
            0x13 => cart_type!(MbcType::Mbc3, has_ram, has_battery),
            0x19 => cart_type!(MbcType::Mbc5,),
            0x1A => cart_type!(MbcType::Mbc5, has_ram),
            0x1B => cart_type!(MbcType::Mbc5, has_ram, has_battery),
            0x1C => cart_type!(MbcType::Mbc5, has_rumble),
            0x1D => cart_type!(MbcType::Mbc5, has_rumble, has_ram),
            0x1E => cart_type!(MbcType::Mbc5, has_rumble, has_battery),
            _ => return Err(GameboyError::UnsupportedCartridgeType(cartridge_type)),
        };

        let rom_size = 32 * 1024 * (1 << header[0x148]);
        let ram_size = match header[0x149] {
            _ if mbc == MbcType::Mbc2 => 512,
            2 => 8 * 1024,
            3 => 32 * 1024,
            4 => 128 * 1024,
            5 => 64 * 1024,
            _ => 0,
        };

        Ok(RomHeader {
            mbc,
            rom_size,
            ram_size,
            has_ram,
            has_battery,
            has_rtc,
            has_rumble,
            has_sensor,
        })
    }

    fn as_emu_cart_config(&self) -> u32 {
        1 | ((self.mbc as u32) << 1)
            | ((self.has_ram as u32) << 4)
            | ((self.has_rtc as u32) << 5)
            | ((self.has_rumble as u32) << 6)
    }
}

/// Driver for Gameboy FPGA module
pub struct Gameboy {
    /// Rom header, if this is an emulated cartridge
    rom_header: Option<RomHeader>,
    /// Path to the RAM file, if this is an emulated cartridge.
    ram_path: Option<PathBuf>,
}

impl Gameboy {
    pub fn new() -> Self {
        Gameboy {
            rom_header: None,
            ram_path: None,
        }
    }

    pub fn set_paused(&mut self, paused: bool) -> Result<(), GameboyError> {
        Device::lock()
            .fpga
            .write_u32(REG_CONTROL, 0b10u32 | ((!paused) as u32))?;
        Ok(())
    }

    /// Resets, leaving in a paused state.
    pub fn reset(&mut self) -> Result<(), GameboyError> {
        let mut device = Device::lock();
        device.fpga.write_u32(REG_CONTROL, 0b00)?;
        device.fpga.write_u32(REG_CONTROL, 0b10)?;
        Ok(())
    }

    pub fn set_physical_cartridge(&mut self) -> Result<(), GameboyError> {
        self.ram_path = None;

        let mut device = Device::lock();

        // Hold in reset
        device.fpga.write_u32(REG_CONTROL, 0b00)?;

        // Switch to physical cartridge.
        device.fpga.write_u32(REG_EMU_CART_CONFIG, 0)?;

        // Resume
        device.fpga.write_u32(REG_CONTROL, 0b11)?;

        Ok(())
    }

    pub fn set_emulated_cartridge(&mut self, rom_path: &Path) -> Result<(), GameboyError> {
        let mut device = Device::lock();

        // Hold in reset
        device.fpga.write_u32(REG_CONTROL, 0b00)?;

        // Load ROM
        let mut rom_file = File::open(rom_path)?;
        let mut rom_header = [0u8; 0x150];
        rom_file.read(&mut rom_header)?;
        let rom_header = RomHeader::parse(rom_header)?;
        rom_file.seek(std::io::SeekFrom::Start(0))?;
        log::info!("Loading rom: {:?}", rom_header);

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

        // Load RAM
        let ram_path = rom_path.with_extension("sav");
        match File::open(ram_path.as_path()) {
            Ok(mut ram_file) => {
                log::info!("Loading RAM");

                let mut i = 0u32;
                loop {
                    let n = ram_file.read(&mut buf)?;
                    if n == 0 {
                        break;
                    }
                    device.fpga.sram_write(i, &buf[..n])?;
                    i += n as u32;
                }
            }
            Err(_) => {
                log::info!("Not loading RAM");
            }
        }

        // Configure emulated cartridge control registers
        device
            .fpga
            .write_u32(REG_EMU_CART_CONFIG, rom_header.as_emu_cart_config())?;
        device.fpga.write_u32(REG_EMU_CART_ROM_ADDR, 0)?;
        device
            .fpga
            .write_u32(REG_EMU_CART_ROM_MASK, rom_header.rom_size - 1)?;
        device.fpga.write_u32(REG_EMU_CART_RAM_ADDR, 0)?;
        device
            .fpga
            .write_u32(REG_EMU_CART_RAM_MASK, rom_header.ram_size - 1)?;

        // Resume
        device.fpga.write_u32(REG_CONTROL, 0b11)?;

        self.ram_path = Some(ram_path);
        self.rom_header = Some(rom_header);
        Ok(())
    }

    /// Persists the game save RAM to disk, if using an emulated cartridge.
    pub fn persist_ram(&mut self) -> Result<(), GameboyError> {
        let ram_path = match self.ram_path.as_ref() {
            Some(ram_path) => ram_path,
            None => return Ok(()),
        };

        let ram_size = self.rom_header.as_ref().map_or(0, |h| h.ram_size);
        log::info!("Saving RAM: {}", ram_path.display());

        let mut file = File::create(ram_path)?;
        const CHUNK_SIZE: usize = 8 * 1024;
        let mut buf = vec![0; CHUNK_SIZE].into_boxed_slice();
        let mut address: u32 = 0;
        let mut bytes_left = ram_size as usize;

        let mut device = Device::lock();
        while bytes_left > 0 {
            let to_read = CHUNK_SIZE.min(bytes_left);
            let data = &mut buf[0..to_read];
            device.fpga.sram_read(address, data)?;
            file.write(data)?;
            address += to_read as u32;
            bytes_left -= to_read;
        }

        Ok(())
    }
}
