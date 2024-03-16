pub mod buttons;
mod resources;

pub use crate::device::graphics::Argb1555;
use crate::{
    device::{self, Device},
    gameboy,
};
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
    text::{renderer::CharacterStyle, Alignment, Baseline, Text, TextStyle, TextStyleBuilder},
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

    fn render(&mut self, target: &mut Surface<'_>);
}

const BACKGROUND_COLOR: Argb1555 = Argb1555::new(true, 0x1D, 0x1D, 0x1D);

pub struct MainMenuScreen {
    logo: resources::ResourceImage<'static>,
    menu: SelectWidget<'static>,
    needs_redraw: bool,
}

impl MainMenuScreen {
    pub fn new() -> Self {
        MainMenuScreen {
            logo: resources::logo(),
            menu: SelectWidget::new(&["Run cartridge", "Load ROM file", "Options", "Shutdown"]),
            needs_redraw: true,
        }
    }
}

impl Screen for MainMenuScreen {
    fn handle_event(&mut self, ui: &mut UI, event: Event) {
        match event {
            Event::ButtonPressed(Button::Up) => {
                self.menu.move_up();
                self.needs_redraw = true;
            }
            Event::ButtonPressed(Button::Down) => {
                self.menu.move_down();
                self.needs_redraw = true;
            }
            Event::ButtonPressed(Button::A) => {
                match self.menu.pos {
                    0 => {
                        // Run cartridge
                        ui.set_screen(Box::new(GameScreen::new(None)));
                    }
                    1 => {
                        // Load ROM
                        let rom_path = "/sdcard/roms/Pokemon Crystal.gbc".to_string();
                        ui.set_screen(Box::new(GameScreen::new(Some(rom_path))));
                    }
                    _ => {}
                }
            }
            _ => {}
        }
    }

    fn needs_redraw(&self) -> bool {
        self.needs_redraw
    }

    fn render(&mut self, target: &mut Surface<'_>) {
        self.needs_redraw = false;
        let _ = target
            .bounding_box()
            .into_styled(PrimitiveStyle::with_fill(BACKGROUND_COLOR))
            .draw(target);

        // Logo at the top.
        let _ = Image::new(
            &self.logo,
            target.bounding_box().anchor_point(AnchorPoint::TopCenter)
                + Point::new(-(self.logo.size().width as i32) / 2, 32),
        )
        .draw(target);

        self.menu.render(target, 120, 80, 100);
    }
}

pub struct GameScreen {
    #[allow(unused)]
    rom_path: Option<String>,
    needs_redraw: bool,

    menu: SelectWidget<'static>,
    playing: bool,
}

impl GameScreen {
    pub fn new(rom_path: Option<String>) -> Self {
        // Transfer ROM.
        let mut device = Device::lock();
        match rom_path.as_ref() {
            Some(rom_path) => {
                log::info!("Transferring rom");
                gameboy::set_emulated_cartridge(&mut device, rom_path).unwrap();
                log::info!("Done transferring rom");
            }
            None => {
                gameboy::set_physical_cartridge(&mut device).unwrap();
            }
        }

        GameScreen {
            rom_path,
            needs_redraw: true,
            menu: SelectWidget::new(&["Resume", "Reset", "Main Menu"]),
            playing: true,
        }
    }
}

impl Screen for GameScreen {
    fn handle_event(&mut self, ui: &mut UI, event: Event) {
        if self.playing {
            if matches!(event, Event::ButtonPressed(Button::Home)) {
                self.playing = false;
                self.needs_redraw = true;

                gameboy::set_paused(&mut Device::lock(), true).unwrap();
            }
            return;
        }

        self.needs_redraw = true;
        match event {
            Event::ButtonPressed(Button::Up) => {
                self.menu.move_up();
            }
            Event::ButtonPressed(Button::Down) => {
                self.menu.move_down();
            }
            Event::ButtonPressed(Button::Home) => {
                gameboy::set_paused(&mut Device::lock(), false).unwrap();
                self.playing = true;
            }
            Event::ButtonPressed(Button::A) => {
                match self.menu.pos {
                    0 => {
                        // Resume
                        gameboy::set_paused(&mut Device::lock(), false).unwrap();
                        self.playing = true;
                    }
                    1 => {
                        // Reset
                        let mut device = Device::lock();
                        gameboy::reset(&mut device).unwrap();
                        gameboy::set_paused(&mut device, false).unwrap();
                        self.playing = true;
                    }
                    2 => {
                        // Main Menu
                        ui.set_screen(Box::new(MainMenuScreen::new()))
                    }
                    _ => {}
                }
            }
            _ => self.needs_redraw = false,
        }
    }

    fn render(&mut self, target: &mut Surface<'_>) {
        // Clear background.
        self.needs_redraw = false;
        let _ = target
            .bounding_box()
            .into_styled(PrimitiveStyle::with_fill(Argb1555::transparent()))
            .draw(target);

        if self.playing {
            return;
        }

        // Draw menu style
        let frame_style = PrimitiveStyleBuilder::new()
            .stroke_color(Rgb555::BLACK.into())
            .stroke_width(1)
            .stroke_alignment(StrokeAlignment::Outside)
            .fill_color(BACKGROUND_COLOR)
            .build();
        let menu_box = Rectangle::with_center(target.bounding_box().center(), Size::new(80, 65));
        let _ = menu_box.into_styled(frame_style).draw(target);

        let menu_pos = menu_box.anchor_point(AnchorPoint::TopCenter);
        self.menu.render(target, menu_pos.x, menu_pos.y + 12, 72);
    }

    fn needs_redraw(&self) -> bool {
        self.needs_redraw
    }
}

pub struct SelectWidget<'a> {
    items: &'a [&'a str],
    pos: usize,
}

impl<'a> SelectWidget<'a> {
    pub fn new(items: &'a [&'a str]) -> Self {
        SelectWidget { items, pos: 0 }
    }

    pub fn move_up(&mut self) {
        if self.pos > 0 {
            self.pos -= 1;
        }
    }
    pub fn move_down(&mut self) {
        if self.pos < self.items.len() - 1 {
            self.pos += 1
        }
    }

    pub fn render(&self, target: &mut Surface<'a>, x: i32, y: i32, w: u32) {
        let mut y = y + 4;
        let text_style = MonoTextStyle::new(&FONT_6X10, Rgb555::BLACK.into());
        let select_outline_style = PrimitiveStyleBuilder::new()
            .stroke_color(Rgb555::BLACK.into())
            .stroke_width(1)
            .stroke_alignment(StrokeAlignment::Outside)
            .build();

        for (i, &item) in self.items.iter().enumerate() {
            let text_pos = Point::new(x, y);
            let text = Text::with_text_style(
                item,
                text_pos,
                text_style,
                TextStyleBuilder::new()
                    .alignment(Alignment::Center)
                    .baseline(Baseline::Middle)
                    .build(),
            );
            let text_height = text.bounding_box().size.height;
            let _ = text.draw(target);
            if self.pos == i {
                let _ = Rectangle::with_center(
                    Point::new(x, y + (text_height / 2) as i32 - 6),
                    Size::new(w, text_height + 4),
                )
                .into_styled(select_outline_style)
                .draw(target);
            }
            y += text_height as i32 + 7;
        }
    }
}
