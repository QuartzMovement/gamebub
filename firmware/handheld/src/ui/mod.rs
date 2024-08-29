pub mod buttons;
mod slint;
mod state;

use std::{cell::RefCell, rc::Rc, sync::mpsc::Receiver, time::Instant};

pub use buttons::{Button, ButtonEvent};

use ::slint::{
    platform::software_renderer::{
        LineBufferProvider, MinimalSoftwareWindow, RepaintBufferType, TargetPixel,
    },
    PhysicalSize, Timer,
};

use crate::device::{self, drivers::fuel_gauge, kvs, Device, Event};

use self::{slint::Argb1555, state::UiState};

const DISPLAY_WIDTH: usize = 240;
const DISPLAY_HEIGHT: usize = 160;

struct LineRenderer<'a, 'b, 'c> {
    device: &'a mut Device<'b>,
    line_buffer: &'c mut [Argb1555],
}

impl<'a, 'b, 'c> LineBufferProvider for &mut LineRenderer<'a, 'b, 'c> {
    type TargetPixel = Argb1555;

    fn process_line(
        &mut self,
        line: usize,
        range: core::ops::Range<usize>,
        render_fn: impl FnOnce(&mut [Self::TargetPixel]),
    ) {
        let start = ((DISPLAY_WIDTH * line) + range.start) * 2;
        let buffer = &mut self.line_buffer[range];
        render_fn(buffer);

        let slice = {
            let len = buffer.len() * 2;
            unsafe { std::slice::from_raw_parts(buffer.as_ptr() as *const u8, len) }
        };
        let _ = self.device.fpga.write_overlay(start as u32, slice);
    }
}

#[allow(unused)]
pub struct UI {
    framebuffer: Vec<Argb1555>,
    window: Rc<MinimalSoftwareWindow>,
    event_queue: Receiver<Event>,
    root: slint::MainWindow,
    state: Rc<RefCell<UiState>>,
}

impl UI {
    pub fn new(device: &mut Device) -> Self {
        let framebuffer = vec![Argb1555::from_rgb(0, 0, 0); DISPLAY_WIDTH];

        let window = MinimalSoftwareWindow::new(RepaintBufferType::ReusedBuffer);
        ::slint::platform::set_platform(Box::new(slint::HandheldPlatform {
            window: window.clone(),
        }))
        .unwrap();
        window.set_size(PhysicalSize::new(
            DISPLAY_WIDTH as u32,
            DISPLAY_HEIGHT as u32,
        ));

        let root = slint::MainWindow::new().unwrap();
        let event_queue = device.take_event_receiver().unwrap();

        let ui = UI {
            framebuffer,
            window,
            event_queue,
            state: UiState::new(&root, device),
            root,
        };
        ui
    }

    pub fn run(&mut self) -> ! {
        let mut button_event_detector = buttons::ButtonEventDetector::new();

        let mut first_render = true;
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
                    device::Event::FpgaIrq(irq_mask) => {
                        if irq_mask & 0b1 != 0 {
                            // Module vblank
                            if let Some(bitstream) = crate::bitstream::current().get() {
                                bitstream.on_vblank_irq();
                            }
                        }
                    }
                    device::Event::FuelGaugeAlert(fuel_gauge::Alert::ChargeChange) => {
                        self.state
                            .borrow_mut()
                            .update_battery_level(&mut Device::lock());
                    }
                    device::Event::HeadphoneState(has_headphones) => {
                        log::info!("Headphone detection: {}", has_headphones);
                        let mut device = Device::lock();
                        device.dac.set_headphones_enabled(has_headphones).unwrap();
                        device.dac.set_speakers_enabled(!has_headphones).unwrap();
                    }
                    _ => {
                        log::info!("event: {:?}", event);
                    }
                }
                pending_event = self.event_queue.try_recv().ok();
            }
            for button_event in button_event_detector.update(None) {
                self.window.dispatch_event(button_event.into());
            }

            ::slint::platform::update_timers_and_animations();

            // TODO additional application logic

            // Render UI if needed.
            self.window.draw_if_needed(|renderer| {
                let mut device = Device::lock();
                let render_start = Instant::now();
                let mut line_buffer = LineRenderer {
                    device: &mut device,
                    line_buffer: &mut self.framebuffer,
                };
                renderer.render_by_line(&mut line_buffer);
                let render_duration = render_start.elapsed();

                log::info!("Render + display {}ms", render_duration.as_millis() as u32);

                // TODO: only need to do this when switching overlays
                let _ = line_buffer
                    .device
                    .fpga
                    .set_overlay_bounds(0x0, 0xFF, 0x0, 0x0, 0xFF, 0x0);

                if first_render {
                    first_render = false;
                    line_buffer
                        .device
                        .set_brightness(kvs::keys::BRIGHTNESS.get().unwrap());
                }
            });

            // Trigger a timer to wake us up for button repeat events.
            if let Some(wakeup) = button_event_detector.next_wakeup_time() {
                Timer::single_shot(wakeup.saturating_duration_since(Instant::now()), || ());
            }

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
