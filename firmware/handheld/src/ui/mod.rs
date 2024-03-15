pub mod buttons;
mod resources;

pub use crate::device::graphics::Argb1555;
use crate::device::{self, Device};
use std::convert::Infallible;

pub use buttons::{Button, ButtonEvent};
use embedded_graphics::{
    draw_target::DrawTarget,
    geometry::{AnchorPoint, Dimensions, OriginDimensions, Size},
    image::Image,
    mono_font::{ascii::FONT_6X10, MonoTextStyle},
    pixelcolor::{Rgb555, WebColors},
    prelude::*,
    primitives::{Primitive, PrimitiveStyle, PrimitiveStyleBuilder, Rectangle, StrokeAlignment},
    text::{Alignment, Text, TextStyleBuilder},
    Drawable,
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

pub struct UI {
    framebuffer: Box<device::graphics::Framebuffer>,
    screen: Option<Box<dyn Screen>>,
}

impl UI {
    pub fn new() -> Self {
        UI {
            framebuffer: Box::new(device::graphics::Framebuffer::new()),
            screen: None,
        }
    }

    pub fn handle_event(&mut self, event: Event) {
        if let Some(mut screen) = self.screen.take() {
            screen.handle_event(self, event);
            self.screen.get_or_insert(screen);
        }
    }

    pub fn render(&mut self) -> bool {
        if let Some(screen) = self.screen.as_mut() {
            let mut surface = Surface::new(&mut self.framebuffer);
            if screen.needs_redraw() {
                screen.render(&mut surface);
                return true;
            }
        }
        false
    }

    pub fn set_screen(&mut self, screen: Box<dyn Screen>) {
        self.screen = Some(screen);
    }

    pub fn framebuffer(&self) -> &device::graphics::Framebuffer {
        &self.framebuffer
    }
}

pub trait Screen {
    fn handle_event(&mut self, ui: &mut UI, event: Event);

    fn needs_redraw(&self) -> bool;

    fn render(&self, target: &mut Surface<'_>);
}

const BACKGROUND_COLOR: Argb1555 = Argb1555::new(true, 0x1D, 0x1D, 0x1D);

pub struct MainMenuScreen {
    logo: resources::ResourceImage<'static>,
    needs_redraw: bool,
}

impl MainMenuScreen {
    pub fn new() -> Self {
        MainMenuScreen {
            logo: resources::logo(),
            needs_redraw: true,
        }
    }
}

impl Screen for MainMenuScreen {
    fn handle_event(&mut self, ui: &mut UI, event: Event) {
        match event {
            Event::ButtonPressed(Button::A) => {
                log::info!("A pressed!");
                let rom_path = "/sdcard/roms/Pokemon Crystal.gbc".to_string();
                ui.set_screen(Box::new(GameScreen::new(Some(rom_path))));
            }
            Event::ButtonPressed(Button::B) => {
                ui.set_screen(Box::new(GameScreen::new(None)));
            }
            _ => {}
        }
    }

    fn needs_redraw(&self) -> bool {
        self.needs_redraw
    }

    fn render(&self, target: &mut Surface<'_>) {
        let _ = target
            .bounding_box()
            .into_styled(PrimitiveStyle::with_fill(BACKGROUND_COLOR))
            .draw(target);

        // Logo at the top.
        let _ = Image::new(
            &self.logo,
            target.bounding_box().anchor_point(AnchorPoint::TopCenter)
                + Point::new(-(self.logo.size().width as i32) / 2, 16),
        )
        .draw(target);

        // Press A to continue
        let text_style = TextStyleBuilder::new().alignment(Alignment::Center).build();
        let character_style = MonoTextStyle::new(&FONT_6X10, Rgb555::CSS_PURPLE.into());
        let _ = Text::with_text_style(
            "Press A for ROM\nPress B for cartridge",
            target
                .bounding_box()
                .anchor_point(AnchorPoint::BottomCenter)
                + Point::new(0, -40),
            character_style,
            text_style,
        )
        .draw(target);
    }
}

pub struct GameScreen {
    #[allow(unused)]
    rom_path: Option<String>,
    needs_redraw: bool,
    value: u32,
}

impl GameScreen {
    pub fn new(rom_path: Option<String>) -> Self {
        // Transfer ROM.
        let mut device = Device::lock();
        match rom_path.as_ref() {
            Some(rom_path) => {
                log::info!("Transferring rom");
                crate::gameboy::set_emulated_cartridge(&mut device, rom_path).unwrap();
                log::info!("Done transferring rom");
            }
            None => {
                crate::gameboy::set_physical_cartridge(&mut device).unwrap();
            }
        }

        GameScreen {
            rom_path,
            needs_redraw: true,
            value: 0,
        }
    }
}

impl Screen for GameScreen {
    fn handle_event(&mut self, _ui: &mut UI, event: Event) {
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
        let _ = target
            .bounding_box()
            .into_styled(PrimitiveStyle::with_fill(Rgb555::BLACK.into()))
            .draw(target);

        // Draw some text.
        let text = format!("Game\nScreen\n{}", self.value);
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
