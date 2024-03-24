use std::rc::Rc;
use std::time::Instant;

use slint::platform::software_renderer::{
    MinimalSoftwareWindow, PremultipliedRgbaColor, TargetPixel,
};
use slint::platform::Platform;

static INITIAL_INSTANT: std::sync::OnceLock<std::time::Instant> = std::sync::OnceLock::new();

pub struct HandheldPlatform {
    pub window: Rc<MinimalSoftwareWindow>,
}

impl Platform for HandheldPlatform {
    fn create_window_adapter(
        &self,
    ) -> Result<std::rc::Rc<dyn slint::platform::WindowAdapter>, slint::PlatformError> {
        Ok(self.window.clone())
    }

    fn duration_since_start(&self) -> core::time::Duration {
        let the_beginning = *INITIAL_INSTANT.get_or_init(Instant::now);
        Instant::now() - the_beginning
    }
}

slint::include_modules!();

#[derive(Copy, Clone, Eq, PartialEq, Ord, PartialOrd, Hash, Debug, Default)]
#[repr(transparent)]
pub struct Argb1555(u16);

impl Argb1555 {
    #![allow(dead_code)]
    const TRANSPARENT: Self = Argb1555(0);

    const A_MASK: u16 = 0b1000_0000_0000_0000;
    const R_MASK: u16 = 0b0111_1100_0000_0000;
    const G_MASK: u16 = 0b0000_0011_1110_0000;
    const B_MASK: u16 = 0b0000_0000_0001_1111;

    /// Return the red component in the range 0..=255
    fn red(self) -> u8 {
        ((self.0 & Self::R_MASK) >> 7) as u8
    }

    /// Return the green component in the range 0..=255
    fn green(self) -> u8 {
        ((self.0 & Self::G_MASK) >> 2) as u8
    }

    /// Return the blue component in the range 0..=255
    fn blue(self) -> u8 {
        ((self.0 & Self::B_MASK) << 3) as u8
    }
}

impl TargetPixel for Argb1555 {
    fn blend(&mut self, color: PremultipliedRgbaColor) {
        let a = ((u8::MAX - color.alpha) as u32) >> 3;

        // NEW: 000000ggggg000000rrrrr00000bbbbb
        let expanded = (self.0 & (Self::R_MASK | Self::B_MASK)) as u32
            | (((self.0 & Self::G_MASK) as u32) << 16);

        // NEW: 0gggggggg000rrrrrrrr00bbbbbbbb00
        let c =
            ((color.red as u32) << 12) | ((color.green as u32) << 23) | ((color.blue as u32) << 2);

        // NEW: 0ggggg000000rrrrr00000bbbbb00000
        let c = c & 0b01111100000011111000001111100000;

        let res = expanded * a + c;

        self.0 = Self::A_MASK
            | ((res >> 21) as u16 & Self::G_MASK)
            | ((res >> 5) as u16 & (Self::R_MASK | Self::B_MASK));
    }

    /// Create a pixel from RGB888.
    fn from_rgb(r: u8, g: u8, b: u8) -> Self {
        Self(
            Self::A_MASK
                | (((r as u16) << 7) & Self::R_MASK)
                | (((g as u16) << 2) & Self::G_MASK)
                | ((b as u16) >> 3),
        )
    }

    fn background() -> Self {
        Self::TRANSPARENT
    }
}
