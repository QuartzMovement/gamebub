use std::fs::File;

use embedded_graphics::pixelcolor::Rgb555;
use flate2::read::GzDecoder;

use device::Device;

use crate::device::graphics::Argb1555;
use crate::ui::ButtonEvent;

mod device;
mod gameboy;
pub mod ui;

fn main() -> anyhow::Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    log::info!("Initializing device");
    Device::init()?;
    let mut device = Device::lock();

    let paths = std::fs::read_dir("/sdcard").unwrap();
    for path in paths {
        println!("sdcard: {}", path.unwrap().path().display())
    }

    // Test RTC
    let datetime = device.rtc.read_datetime()?;
    match datetime {
        Some(datetime) => log::info!("Current datetime: {:?}", datetime),
        None => {
            log::info!("No date set, resetting");
            device
                .rtc
                .write_datetime(device::drivers::rtc::Datetime::default())?;
        }
    }

    // Program FPGA
    {
        let mut bitstream = GzDecoder::new(File::open("/sdcard/top_handheld_cgb.bit.gz")?);
        device.fpga.program(&mut bitstream)?;
        device.lcd.enable_fpga_control()?;
    }

    // Testing graphics: draw a frame around the game
    {
        use embedded_graphics::{
            geometry::AnchorPoint,
            mono_font::{ascii::FONT_6X10, MonoTextStyle},
            prelude::*,
            primitives::{PrimitiveStyleBuilder, Rectangle, StrokeAlignment},
            text::{Alignment, Text, TextStyleBuilder},
        };

        let mut framebuffer = Box::new(device::graphics::Framebuffer::new());
        log::info!("Drawing framebuffer");

        // Draw some text.
        let text = "this\nis\na\nframe";
        let text_style = TextStyleBuilder::new().alignment(Alignment::Left).build();
        let character_style = MonoTextStyle::new(&FONT_6X10, Rgb555::CSS_LIGHT_CORAL.into());
        Text::with_text_style(
            text,
            framebuffer
                .bounding_box()
                .anchor_point(AnchorPoint::CenterLeft),
            character_style,
            text_style,
        )
        .draw(framebuffer.as_mut())?;

        // Cut out area for the game.
        let frame_style = PrimitiveStyleBuilder::new()
            .stroke_color(Rgb555::WHITE.into())
            .stroke_width(2)
            .stroke_alignment(StrokeAlignment::Outside)
            .fill_color(Argb1555::transparent())
            .build();
        Rectangle::with_center(framebuffer.bounding_box().center(), Size::new(160, 144))
            .into_styled(frame_style)
            .draw(framebuffer.as_mut())?;

        log::info!("Displaying framebuffer");
        device.display_framebuffer(&framebuffer);
    }

    log::info!("transferring rom");
    gameboy::set_emulated_cartridge(&mut device, "/sdcard/roms/Pokemon Silver.gbc")?;
    log::info!("done transferring rom");

    let event_queue = device.take_event_receiver().unwrap();
    std::mem::drop(device); // Drop the lock

    let mut button_event_detector = ui::buttons::ButtonEventDetector::new();

    while let Ok(event) = event_queue.recv() {
        match event {
            device::Event::Button(state) => {
                for button_event in button_event_detector.update(state) {
                    log::info!("event: {:?}", button_event);

                    match button_event {
                        ButtonEvent::Pressed(ui::Button::VolUp) => {
                            Device::lock().dac.set_speakers_enabled(true).unwrap();
                        }
                        ButtonEvent::Pressed(ui::Button::VolDown) => {
                            Device::lock().dac.set_speakers_enabled(false).unwrap();
                        }
                        _ => {}
                    }
                }
            }
            device::Event::FpgaIrq => log::info!("event: fpga irq"),
        }
    }
    panic!("Queue empty");
}
