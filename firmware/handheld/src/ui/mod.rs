pub mod buttons;

pub use crate::device::graphics::Argb1555;
use std::convert::Infallible;

pub use buttons::{Button, ButtonEvent};
use embedded_graphics::{
    draw_target::DrawTarget,
    geometry::{OriginDimensions, Size},
    pixelcolor::Rgb555,
};

#[derive(Copy, Clone, Debug)]
pub enum Event {
    ButtonPressed(Button),
    ButtonReleased(Button),
}

pub struct Surface<'a> {
    framebuffer: &'a mut crate::device::graphics::Framebuffer,
}

impl<'a> Surface<'a> {
    pub fn new(framebuffer: &'a mut crate::device::graphics::Framebuffer) -> Self {
        Surface { framebuffer }
    }
}

impl<'a> DrawTarget for Surface<'a> {
    type Color = Argb1555;

    type Error = Infallible;

    fn draw_iter<I>(&mut self, pixels: I) -> Result<(), Self::Error>
    where
        I: IntoIterator<Item = embedded_graphics::prelude::Pixel<Self::Color>>,
    {
        self.framebuffer.draw_iter(pixels)
    }
}

impl<'a> OriginDimensions for Surface<'a> {
    fn size(&self) -> embedded_graphics::prelude::Size {
        Size::new(240, 160)
    }
}

pub trait Screen {
    fn handle_event(&mut self, event: Event);

    fn needs_redraw(&self) -> bool;

    fn render(&self, target: &mut Surface<'_>);
}

pub struct DemoScreen {
    needs_redraw: bool,
    value: u32,
}

impl DemoScreen {
    pub fn new() -> Self {
        DemoScreen {
            needs_redraw: true,
            value: 0,
        }
    }
}

impl Screen for DemoScreen {
    fn handle_event(&mut self, event: Event) {
        log::info!("DemoScreen event {:?}", event);

        match event {
            Event::ButtonPressed(Button::Up) => {
                self.value = self.value.saturating_add(1);
                self.needs_redraw = true;
            }
            Event::ButtonPressed(Button::Down) => {
                self.value = self.value.saturating_sub(1);
                self.needs_redraw = true;
            }
            _ => {}
        }
    }

    fn render(&self, target: &mut Surface<'_>) {
        use embedded_graphics::{
            geometry::AnchorPoint,
            mono_font::{ascii::FONT_6X10, MonoTextStyle},
            prelude::*,
            primitives::{PrimitiveStyle, PrimitiveStyleBuilder, Rectangle, StrokeAlignment},
            text::{Alignment, Text, TextStyleBuilder},
        };

        log::info!("Drawing DemoScreen");
        let _ = target
            .bounding_box()
            .into_styled(PrimitiveStyle::with_fill(Rgb555::BLACK.into()))
            .draw(target);

        // Draw some text.
        let text = format!("Demo\nScreen\n{}", self.value);
        let text_style = TextStyleBuilder::new().alignment(Alignment::Left).build();
        let character_style = MonoTextStyle::new(&FONT_6X10, Rgb555::CSS_LIGHT_CORAL.into());
        let _ = Text::with_text_style(
            &text,
            target.bounding_box().anchor_point(AnchorPoint::CenterLeft),
            character_style,
            text_style,
        )
        .draw(target);

        // Cut out area for the game.
        let frame_style = PrimitiveStyleBuilder::new()
            .stroke_color(Rgb555::WHITE.into())
            .stroke_width(2)
            .stroke_alignment(StrokeAlignment::Outside)
            .fill_color(Argb1555::transparent())
            .build();
        let _ = Rectangle::with_center(target.bounding_box().center(), Size::new(160, 144))
            .into_styled(frame_style)
            .draw(target);
    }

    fn needs_redraw(&self) -> bool {
        self.needs_redraw
    }
}
