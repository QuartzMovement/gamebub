pub mod buttons;
mod slint;

use std::{rc::Rc, sync::mpsc::Receiver, time::Instant};

pub use buttons::{Button, ButtonEvent};

use ::slint::{
    platform::software_renderer::{MinimalSoftwareWindow, RepaintBufferType, TargetPixel},
    PhysicalSize,
};

use crate::device::{self, Device, Event};

use self::slint::Argb1555;

const DISPLAY_WIDTH: usize = 240;
const DISPLAY_HEIGHT: usize = 160;

pub struct UI {
    framebuffer: Vec<Argb1555>,
    window: Rc<MinimalSoftwareWindow>,
    event_queue: Receiver<Event>,
    _inner: slint::MainWindow,
}

impl UI {
    pub fn new(device: &mut Device) -> Self {
        let framebuffer = vec![Argb1555::from_rgb(0, 0, 0); DISPLAY_WIDTH * DISPLAY_HEIGHT];

        let window = MinimalSoftwareWindow::new(RepaintBufferType::ReusedBuffer);
        ::slint::platform::set_platform(Box::new(slint::HandheldPlatform {
            window: window.clone(),
        }))
        .unwrap();

        let ui = slint::MainWindow::new().unwrap();

        window.set_size(PhysicalSize::new(
            DISPLAY_WIDTH as u32,
            DISPLAY_HEIGHT as u32,
        ));

        let event_queue = device.take_event_receiver().unwrap();

        UI {
            framebuffer,
            window,
            event_queue,
            _inner: ui,
        }
    }

    pub fn run(&mut self) -> ! {
        let mut button_event_detector = buttons::ButtonEventDetector::new();

        let mut pending_event = None;
        loop {
            // Process events.
            while let Some(event) = pending_event {
                match event {
                    device::Event::Button(state) => {
                        for button_event in button_event_detector.update(Some(state)) {
                            self.window.dispatch_event(button_event.into());
                        }
                    }
                    _ => log::info!("event: {:?}", event),
                }
                pending_event = self.event_queue.try_recv().ok();
            }

            ::slint::platform::update_timers_and_animations();

            // TODO additional application logic

            // Render UI if needed.
            self.window.draw_if_needed(|renderer| {
                let render_start = Instant::now();
                renderer.render(&mut self.framebuffer, DISPLAY_WIDTH);
                let render_duration = render_start.elapsed();

                let mut device = Device::lock();

                let display_start = Instant::now();
                let slice = {
                    let len = self.framebuffer.len() * 2;
                    unsafe {
                        std::slice::from_raw_parts(self.framebuffer.as_ptr() as *const u8, len)
                    }
                };
                // TODO: partial rendering based on dirty region.
                device.display_framebuffer_raw(slice);
                let display_duration = display_start.elapsed();

                log::info!(
                    "Render {}ms, display {}ms",
                    render_duration.as_millis() as u32,
                    display_duration.as_millis() as u32
                );

                // TODO don't do this every time
                device.set_brightness(u16::MAX / 4);
            });

            // Sleep until the next animation, timer, or event.
            if !self.window.has_active_animations() {
                match ::slint::platform::duration_until_next_timer_update() {
                    Some(duration) => {
                        pending_event = self.event_queue.recv_timeout(duration).ok();
                    }
                    None => {
                        pending_event = self.event_queue.recv().ok();
                    }
                }
            }
        }
    }
}
