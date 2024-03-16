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

    // Create UI and do initial render.
    log::info!("Initializing UI");
    let mut ui = ui::UI::new();
    ui.set_screen(Box::new(ui::MainMenuScreen::new()));
    ui.render();
    device.display_framebuffer(ui.framebuffer());
    log::info!("Turning on LCD backlight");
    device.set_brightness(u16::MAX / 4);

    let event_queue = device.take_event_receiver().unwrap();
    std::mem::drop(device); // Drop the lock

    let mut button_event_detector = ui::buttons::ButtonEventDetector::new();

    // Main event loop.
    while let Ok(event) = event_queue.recv() {
        match event {
            device::Event::Button(state) => {
                for button_event in button_event_detector.update(state) {
                    log::info!("event: {:?}", button_event);

                    match button_event {
                        ButtonEvent::Pressed(ui::Button::VolUp) => {
                            Device::lock().dac.set_mute(false).unwrap();
                        }
                        ButtonEvent::Pressed(ui::Button::VolDown) => {
                            Device::lock().dac.set_mute(true).unwrap();
                        }
                        _ => {}
                    }

                    match button_event {
                        ButtonEvent::Pressed(button) => {
                            ui.handle_event(ui::Event::ButtonPressed(button))
                        }
                        ButtonEvent::Released(button) => {
                            ui.handle_event(ui::Event::ButtonReleased(button))
                        }
                    }
                }
            }
            device::Event::FpgaIrq => log::info!("event: fpga irq"),
        }

        // TODO handle multiple events before rendering (if multiple are available)
        if ui.render() {
            Device::lock().display_framebuffer(ui.framebuffer());
        }
    }
    panic!("Queue empty");
}
