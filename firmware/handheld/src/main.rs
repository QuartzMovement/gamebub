use std::{
    fs::File,
    time::{Duration, Instant},
};

use flate2::read::GzDecoder;

use device::Device;

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
    let mut next_timeout = Duration::MAX;
    loop {
        let event = event_queue.recv_timeout(next_timeout);

        // First, handle button events.
        if let Some(button_events) = match event {
            Ok(device::Event::Button(state)) => Some(button_event_detector.update(Some(state))),
            Err(_) => Some(button_event_detector.update(None)),
            _ => None,
        } {
            for button_event in button_events {
                log::info!("Button event: {:?}", button_event);

                // temporary: handle volume buttons here
                match button_event {
                    ButtonEvent::Pressed(ui::Button::VolUp, _) => {
                        let dac = &mut Device::lock().dac;
                        let new_volume = dac.get_volume().saturating_add(16);
                        log::info!("Setting volume to {}", new_volume);
                        dac.set_volume(new_volume).unwrap();
                    }
                    ButtonEvent::Pressed(ui::Button::VolDown, _) => {
                        let dac = &mut Device::lock().dac;
                        let new_volume = dac.get_volume().saturating_sub(16);
                        log::info!("Setting volume to {}", new_volume);
                        dac.set_volume(new_volume).unwrap();
                    }
                    _ => {}
                }

                match button_event {
                    ButtonEvent::Pressed(button, _) => {
                        ui.handle_event(ui::Event::ButtonPressed(button))
                    }
                    ButtonEvent::Released(button) => {
                        ui.handle_event(ui::Event::ButtonReleased(button))
                    }
                }
            }
        }

        // Other events.
        match event {
            Ok(device::Event::FpgaIrq) => log::info!("event: fpga irq"),
            _ => {}
        }

        // TODO handle multiple events before rendering (if multiple are available)
        if ui.render() {
            Device::lock().display_framebuffer(ui.framebuffer());
        }

        next_timeout = button_event_detector
            .next_wakeup_time()
            .map_or(Duration::MAX, |t| {
                t.saturating_duration_since(Instant::now())
            });
    }
}
