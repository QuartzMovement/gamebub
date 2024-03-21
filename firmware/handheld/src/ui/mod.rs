pub mod buttons;
mod resources;

pub use crate::device::graphics::Argb1555;
use crate::{
    device::{self, Device},
    gameboy::Gameboy,
};
use std::{
    convert::Infallible,
    path::{Path, PathBuf},
};

pub use buttons::{Button, ButtonEvent};
use embedded_graphics::{
    draw_target::DrawTarget,
    geometry::{AnchorPoint, AnchorX, Dimensions, OriginDimensions, Size},
    image::Image,
    mono_font::{
        ascii::{FONT_6X10, FONT_7X13_BOLD},
        MonoTextStyle,
    },
    pixelcolor::Rgb555,
    prelude::*,
    primitives::{
        Primitive, PrimitiveStyle, PrimitiveStyleBuilder, Rectangle, StrokeAlignment,
        StyledDrawable, Triangle,
    },
    text::{Alignment, Baseline, Text, TextStyleBuilder},
    Drawable,
};

#[derive(Copy, Clone, Debug)]
pub enum Event {
    ButtonPressed(Button, bool),
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
            Event::ButtonPressed(Button::Up, _) => {
                self.menu.move_up();
                self.needs_redraw = true;
            }
            Event::ButtonPressed(Button::Down, _) => {
                self.menu.move_down();
                self.needs_redraw = true;
            }
            Event::ButtonPressed(Button::A, false) => {
                match self.menu.pos {
                    0 => {
                        // Run cartridge
                        let mut gameboy = Gameboy::new();
                        gameboy.set_physical_cartridge().unwrap();
                        ui.set_screen(Box::new(GameScreen::new(gameboy)));
                    }
                    1 => {
                        // Load ROM
                        let root_path = "/sdcard/roms";
                        ui.set_screen(Box::new(RomSelectScreen::new(root_path.as_ref())));
                    }
                    3 => {
                        // Shutdown
                        Device::lock().power_off();
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
    needs_redraw: bool,

    gameboy: Gameboy,
    menu: SelectWidget<'static>,
    playing: bool,
}

impl GameScreen {
    pub fn new(gameboy: Gameboy) -> Self {
        GameScreen {
            gameboy,
            needs_redraw: true,
            menu: SelectWidget::new(&["Resume", "Reset", "Main Menu"]),
            playing: true,
        }
    }
}

impl Screen for GameScreen {
    fn handle_event(&mut self, ui: &mut UI, event: Event) {
        if self.playing {
            if matches!(event, Event::ButtonPressed(Button::Home, false)) {
                self.playing = false;
                self.needs_redraw = true;

                self.gameboy.set_paused(true).unwrap();
                // TODO handle error more gracefully
                self.gameboy.persist_ram().unwrap();
            }
            return;
        }

        self.needs_redraw = true;
        match event {
            Event::ButtonPressed(Button::Up, _) => {
                self.menu.move_up();
            }
            Event::ButtonPressed(Button::Down, _) => {
                self.menu.move_down();
            }
            Event::ButtonPressed(Button::Home, false) => {
                self.gameboy.set_paused(false).unwrap();
                self.playing = true;
            }
            Event::ButtonPressed(Button::A, false) => {
                match self.menu.pos {
                    0 => {
                        // Resume
                        self.gameboy.set_paused(false).unwrap();
                        self.playing = true;
                    }
                    1 => {
                        // Reset
                        self.gameboy.reset().unwrap();
                        self.gameboy.set_paused(false).unwrap();
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

pub struct RomSelectScreen {
    root_path: PathBuf,
    needs_redraw: bool,
    widget: ListWidget,
}

impl RomSelectScreen {
    pub fn new(root_path: &Path) -> RomSelectScreen {
        let files = Self::get_files(root_path).unwrap();

        RomSelectScreen {
            root_path: root_path.to_owned(),
            needs_redraw: true,
            widget: ListWidget::new(files, 10),
        }
    }

    fn get_files(path: &Path) -> std::io::Result<Vec<String>> {
        let mut files = path
            .read_dir()?
            .filter_map(|e| e.ok())
            .filter(|f| f.metadata().is_ok_and(|m| m.is_file()))
            .filter(|f| {
                f.file_name().to_str().is_some_and(|n| {
                    !n.starts_with(".") && (n.ends_with(".gbc") || n.ends_with(".gb"))
                })
            })
            .filter_map(|f| f.file_name().into_string().ok())
            .collect::<Vec<_>>();
        files.sort();
        Ok(files)
    }
}

impl Screen for RomSelectScreen {
    fn handle_event(&mut self, ui: &mut UI, event: Event) {
        match event {
            Event::ButtonPressed(Button::Up, _) => {
                self.widget.move_up();
                self.needs_redraw = true;
            }
            Event::ButtonPressed(Button::Down, _) => {
                self.widget.move_down();
                self.needs_redraw = true;
            }
            Event::ButtonPressed(Button::A, false) => {
                let item = self.widget.selected();
                let path = self.root_path.join(item);
                log::info!("Selected ROM {}", path.display());
                let mut gameboy = Gameboy::new();
                match gameboy.set_emulated_cartridge(path.as_path()) {
                    Ok(_) => {
                        ui.set_screen(Box::new(GameScreen::new(gameboy)));
                    }
                    Err(e) => {
                        log::error!("Error loading ROM: {:?}", e);
                        // TODO show an error message
                        self.needs_redraw = true;
                    }
                }
            }
            Event::ButtonPressed(Button::B, false) => {
                ui.set_screen(Box::new(MainMenuScreen::new()))
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

        let text_heading = MonoTextStyle::new(&FONT_7X13_BOLD, Rgb555::BLACK.into());
        let text_body = MonoTextStyle::new(&FONT_6X10, Rgb555::BLACK.into());

        // Heading and instructions
        let _ = Text::with_baseline(
            "Load ROM file...",
            Point::new(8, 4),
            text_heading,
            Baseline::Top,
        )
        .draw(target);
        let _ = Text::with_baseline(
            "A: Select                     B: Back",
            target.bounding_box().anchor_point(AnchorPoint::BottomLeft) + Point::new(8, -2),
            text_body,
            Baseline::Bottom,
        )
        .draw(target);

        let list_rect =
            Rectangle::with_corners(Point::new(4, 20), Point::new(-4, -16) + target.size());
        self.widget.render(target, list_rect);
    }
}

pub struct ListWidget {
    items: Vec<String>,
    pos: usize,
    start: usize,
    num_lines: usize,
}

impl ListWidget {
    pub fn new(items: Vec<String>, num_lines: usize) -> Self {
        ListWidget {
            items,
            pos: 0,
            start: 0,
            num_lines,
        }
    }

    pub fn selected(&self) -> &str {
        &self.items[self.pos]
    }

    pub fn move_up(&mut self) {
        if self.pos > 0 {
            self.pos -= 1;
            if self.pos < self.start {
                self.start -= 1;
            }
        } else {
            self.pos = self.items.len() - 1;
            self.start = self.items.len().saturating_sub(self.num_lines)
        }
    }
    pub fn move_down(&mut self) {
        if self.pos < self.items.len() - 1 {
            self.pos += 1;
            if self.pos >= (self.start + self.num_lines) {
                self.start += 1;
            }
        } else {
            self.pos = 0;
            self.start = 0;
        }
    }

    pub fn render(&self, target: &mut Surface<'_>, bounds: Rectangle) {
        let text_style = TextStyleBuilder::new()
            .alignment(Alignment::Left)
            .baseline(Baseline::Top)
            .build();
        let character_style = MonoTextStyle::new(&FONT_6X10, Rgb555::BLACK.into());
        let line_pitch = character_style.font.character_size.height + 2;
        let lines = (bounds.size.height / line_pitch) as usize;

        let outline_style = PrimitiveStyle::with_stroke(Rgb555::BLACK.into(), 1);
        let fill_style = PrimitiveStyle::with_fill(Rgb555::BLACK.into());

        let pointer = Triangle::new(Point::new(0, 0), Point::new(-4, -4), Point::new(-4, 4))
            .translate(Point::new(
                -3,
                (character_style.font.character_size.height as i32 / 2) - 1,
            ))
            .into_styled(fill_style);

        // Draw outline
        let _ = bounds
            .resized_width(bounds.size.width - 4, AnchorX::Left)
            .draw_styled(&outline_style, target);

        // Draw list
        let mut text_position = bounds.top_left + Point::new(10, 4);
        for i in self.start..(self.start + lines) {
            if i == self.pos {
                let _ = pointer.translate(text_position).draw(target);
            }

            let line = &self.items[i];
            let text = Text::with_text_style(line, text_position, character_style, text_style);

            let max_width = bounds.size.width - 12;
            if text.bounding_box().size.width > max_width {
                // Ellipsis
                let length = ((max_width / character_style.font.character_size.width) as usize)
                    .min(line.len())
                    - 3;
                let _ = Text::with_text_style(
                    &format!("{}...", &line[..length]),
                    text_position,
                    character_style,
                    text_style,
                )
                .draw(target);
            } else {
                let _ = text.draw(target);
            }
            text_position.y += line_pitch as i32;
        }

        // Draw scroll bar
        if self.items.len() > self.num_lines {
            let unit = (bounds.size.height as f32) / (self.items.len() as f32);
            let y = (unit * self.start as f32) as i32;
            let h = (unit * self.num_lines as f32) as u32;
            let _ = Rectangle::new(
                bounds.anchor_point(AnchorPoint::TopRight) + Point::new(-2, y),
                Size::new(2, h),
            )
            .draw_styled(&fill_style, target);
        }
    }
}
