use embedded_graphics::framebuffer::buffer_size;
use embedded_graphics::pixelcolor::raw::LittleEndian;
use embedded_graphics::pixelcolor::Rgb555;
use embedded_graphics::pixelcolor::{raw::RawU16, PixelColor};

pub type Framebuffer = embedded_graphics::framebuffer::Framebuffer<
    Argb1555,
    RawU16,
    LittleEndian,
    240,
    160,
    { buffer_size::<Argb1555>(240, 160) },
>;

#[derive(Copy, Clone, Eq, PartialEq, Ord, PartialOrd, Hash, Debug, Default)]
pub struct Argb1555(RawU16);

impl Argb1555 {
    pub const fn new(alpha: bool, red: u8, green: u8, blue: u8) -> Self {
        // TODO: fix this so that alpha true means opaque
        Self(RawU16::new(
            (((!alpha) as u16) << 15)
                | (((red as u16) & 0x1F) << 10)
                | (((green as u16) & 0x1F) << 5)
                | ((blue as u16) & 0x1F),
        ))
    }

    pub const fn transparent() -> Self {
        Self::new(false, 0, 0, 0)
    }
}

impl Into<RawU16> for Argb1555 {
    fn into(self) -> RawU16 {
        self.0
    }
}

impl PixelColor for Argb1555 {
    type Raw = RawU16;
}

impl From<RawU16> for Argb1555 {
    fn from(data: RawU16) -> Self {
        Self(data)
    }
}

impl From<Rgb555> for Argb1555 {
    fn from(color: Rgb555) -> Self {
        Self(color.into())
    }
}
