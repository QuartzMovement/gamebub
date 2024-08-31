pub mod buttons;
mod slint;
mod state;

use ::slint::platform::WindowAdapter;
use ::slint::{ComponentHandle, WindowSize};
pub use buttons::{Button, ButtonEvent, ButtonMap};
use std::sync::{mpsc, OnceLock};
use std::{cell::RefCell, rc::Rc, sync::mpsc::Receiver, time::Instant};

use ::slint::{
    platform::software_renderer::{LineBufferProvider, RepaintBufferType, TargetPixel},
    PhysicalSize, Timer,
};

use crate::device::{kvs, Device};

use self::{slint::Argb1555, slint::MinimalSoftwareWindow, state::UiState};

const DISPLAY_WIDTH: usize = 240;
const DISPLAY_HEIGHT: usize = 160;

#[derive(Debug)]
pub enum Message {
    /// The state of the buttons have changed.
    Button(ButtonMap),
    /// Battery status has changed.
    BatteryStatus { level: f32 },
    /// Redraw the entire screen (e.g. after a display change)
    Redraw,
    /// Go to the "Game" screen
    EnterGame,
    /// ROM loading progress
    RomLoadingProgress(f32),
}

/// Send a message to the UI thread.
pub fn send(message: Message) {
    match SENDER.get() {
        Some(sender) => sender.send(message).unwrap(),
        None => log::error!("Dropping UI message {:?}", message),
    }
}

static SENDER: OnceLock<mpsc::Sender<Message>> = OnceLock::new();

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
    message_queue: Receiver<Message>,
    root: slint::MainWindow,
    state: Rc<RefCell<UiState>>,
}

impl UI {
    pub fn new(device: &mut Device) -> Self {
        let (sender, receiver) = mpsc::channel::<Message>();
        SENDER.set(sender).expect("UI already initialized");

        let framebuffer = vec![Argb1555::from_rgb(0, 0, 0); DISPLAY_WIDTH];

        let window = MinimalSoftwareWindow::new(RepaintBufferType::ReusedBuffer);
        ::slint::platform::set_platform(Box::new(slint::HandheldPlatform {
            window: window.clone(),
        }))
        .unwrap();
        window.set_size(WindowSize::Physical(PhysicalSize::new(
            DISPLAY_WIDTH as u32,
            DISPLAY_HEIGHT as u32,
        )));

        let root = slint::MainWindow::new().unwrap();

        let ui = UI {
            framebuffer,
            window,
            message_queue: receiver,
            state: UiState::new(&root, device),
            root,
        };
        ui
    }

    pub fn run(&mut self) -> ! {
        let mut button_event_detector = buttons::ButtonEventDetector::new();

        let mut first_render = true;
        let mut pending_message = None;
        loop {
            // Process messages.
            while let Some(message) = pending_message {
                match message {
                    Message::Button(state) => {
                        for button_event in button_event_detector.update(Some(state)) {
                            self.window.dispatch_event(button_event.into());
                        }
                    }
                    Message::BatteryStatus { level } => {
                        self.state.borrow_mut().update_battery_level(level);
                    }
                    Message::Redraw => {
                        log::info!("Refreshing screen");
                        self.window.request_redraw();

                        // Force renderer to clear the dirty region and re-render everything.
                        self.window
                            .renderer
                            .set_repaint_buffer_type(RepaintBufferType::NewBuffer);
                    }
                    Message::EnterGame => {
                        self.root.invoke_set_screen(slint::ScreenId::Game);
                    }
                    Message::RomLoadingProgress(progress) => {
                        // Update loading bar, in increments of 10%.
                        self.root
                            .global::<slint::Backend>()
                            .set_rom_select_progress((progress * 10.0).ceil() * 10.0);
                    }
                    #[allow(unreachable_patterns)]
                    _ => {
                        log::warn!("Unhandled message: {:?}", message);
                    }
                }
                pending_message = self.message_queue.try_recv().ok();
            }
            for button_event in button_event_detector.update(None) {
                self.window.dispatch_event(button_event.into());
            }

            ::slint::platform::update_timers_and_animations();

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

                log::info!("Render + display {}ms", render_duration.as_millis() as u32,);

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

                // If we changed the repaint buffer type to force a redraw, change it back.
                if renderer.repaint_buffer_type() == RepaintBufferType::NewBuffer {
                    renderer.set_repaint_buffer_type(RepaintBufferType::ReusedBuffer);
                    // For some reason, this doesn't get called automatically after the first render.
                    self.window.request_redraw();
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
                        pending_message = self.message_queue.recv_timeout(duration).ok();
                    }
                    None => {
                        pending_message = self.message_queue.recv().ok();
                    }
                }
            }
        }
    }
}
