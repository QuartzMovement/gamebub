use std::sync::{Mutex, MutexGuard};
use std::time::Duration;

use crate::device::DisplayMode;
use crate::device::{drivers::fpga, Device};
use crate::ui;

use flate2::read::GzDecoder;

pub mod boot;
pub mod gameboy;
pub mod gba;

mod util;

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
    let display_mode = device.get_display_mode();

    if let DisplayMode::Internal = display_mode {
        // Avoid LCD artifacts during FPGA reprogram.
        device.lcd.enter_sleep().unwrap();
        // For some reason, we need to sleep for a short amount of time here
        // (before doing FPGA program), otherwise the LCD won't properly sleep.
        // 2 ms is sometimes sufficient, 5 ms is always sufficient, 10 ms seems to always work.
        std::thread::sleep(Duration::from_millis(10));
    }

    let file = crate::util::open_system_file(path).unwrap();
    let mut bitstream = GzDecoder::new(file);
    device.fpga.program(&mut bitstream).unwrap();
    device.fpga.set_display_mode(display_mode).unwrap();
    device.fpga.enable_interrupt(fpga::Irq::Button).unwrap();
    ui::send(ui::Message::Redraw);

    if let DisplayMode::Internal = display_mode {
        device.lcd.exit_sleep().unwrap();
    }
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

    /// Ensure the boot is loaded.
    pub fn ensure_boot(&mut self) -> Result<(), String> {
        match self {
            CurrentBitstream::None => Ok(()),
            _ => {
                program_fpga("boot.bit.gz");
                self.set(CurrentBitstream::None)
            }
        }
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
