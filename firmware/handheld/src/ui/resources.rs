use embedded_graphics::draw_target::DrawTargetExt;
use embedded_graphics::geometry::Dimensions;
use embedded_graphics::{
    draw_target::DrawTarget,
    geometry::{OriginDimensions, Size},
    image::ImageDrawable,
};
use tinyqoi::Qoi;

use super::Argb1555;

static LOGO_QOI: &[u8] = include_bytes!("logo.qoi");

pub struct ResourceImage<'a> {
    qoi: Qoi<'a>,
}

impl OriginDimensions for ResourceImage<'_> {
    fn size(&self) -> Size {
        self.qoi.size()
    }
}

impl ImageDrawable for ResourceImage<'_> {
    type Color = Argb1555;

    fn draw<D>(&self, target: &mut D) -> Result<(), D::Error>
    where
        D: DrawTarget<Color = Self::Color>,
    {
        target.fill_contiguous(
            &self.qoi.bounding_box(),
            self.qoi.pixels().map(Argb1555::from),
        )
    }

    fn draw_sub_image<D>(
        &self,
        target: &mut D,
        area: &embedded_graphics::primitives::Rectangle,
    ) -> Result<(), D::Error>
    where
        D: DrawTarget<Color = Self::Color>,
    {
        self.draw(&mut target.translated(-area.top_left).clipped(area))
    }
}

pub fn logo() -> ResourceImage<'static> {
    ResourceImage {
        qoi: Qoi::new(LOGO_QOI).unwrap(),
    }
}
