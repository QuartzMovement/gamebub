use crate::device::drivers::fpga;

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
