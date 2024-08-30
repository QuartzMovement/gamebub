use std::{
    fs::File,
    sync::{Mutex, MutexGuard},
};

use crate::device::{drivers::fpga, Device};
use crate::ui;

use flate2::read::GzDecoder;

pub mod gameboy;
pub mod gba;

/// Driver for a specific bitstream.
pub trait Bitstream {
    /// Get the path for the bitstream.
    fn get_bitstream_path(&self) -> &'static str;

    /// Do final initialization after programming the bitstream.
    fn on_after_program(&mut self) -> Result<(), String>;

    /// Set whether the inner design is paused.
    fn set_paused(&mut self, paused: bool) -> Result<(), fpga::Error>;

    /// Reset the inner design, leaving it paused.
    fn reset(&mut self) -> Result<(), fpga::Error>;

    /// Called when a vblank IRQ occurs.
    fn on_vblank_irq(&mut self);
}

/// The current global bitstream, behind a lock.
static CURRENT: Mutex<CurrentBitstream> = Mutex::new(CurrentBitstream::None);

/// Lock and return the current bitstream.
pub fn current() -> MutexGuard<'static, CurrentBitstream> {
    CURRENT.lock().unwrap()
}

fn program_fpga(path: &str) {
    log::info!("Loading bitstream {}", path);
    let mut device = Device::lock();
    let file = File::open(path).unwrap();
    let mut bitstream = GzDecoder::new(file);
    device.lcd.enable_mcu_control().unwrap();
    device.fpga.program(&mut bitstream).unwrap();
    device.lcd.enable_fpga_control().unwrap();
    ui::send(ui::Message::Redraw);
}

pub enum CurrentBitstream {
    None,
    Gameboy(gameboy::Gameboy),
    Gba(gba::Gba),
}

impl CurrentBitstream {
    pub fn get(&mut self) -> Option<&mut dyn Bitstream> {
        match self {
            CurrentBitstream::None => None,
            CurrentBitstream::Gameboy(x) => Some(x),
            CurrentBitstream::Gba(x) => Some(x),
        }
    }

    fn set(&mut self, new: CurrentBitstream) -> Result<(), String> {
        *self = new;
        if let Some(bitstream) = self.get() {
            program_fpga(bitstream.get_bitstream_path());
            bitstream.on_after_program()?;
        }
        Ok(())
    }

    /// Ensure the gameboy bitstream is loaded.
    pub fn ensure_gameboy(&mut self) -> Result<(), String> {
        match self {
            CurrentBitstream::Gameboy(_) => Ok(()),
            _ => {
                let bitstream = gameboy::Gameboy::new();
                self.set(CurrentBitstream::Gameboy(bitstream))
            }
        }
    }

    /// Ensure the GBA bitstream is loaded.
    pub fn ensure_gba(&mut self) -> Result<(), String> {
        match self {
            CurrentBitstream::Gba(_) => Ok(()),
            _ => {
                let bitstream = gba::Gba::new();
                self.set(CurrentBitstream::Gba(bitstream))
            }
        }
    }
}
