pub mod buttons;
mod slint;

use std::{cell::RefCell, path::Path, rc::Rc, sync::mpsc::Receiver, time::Instant};

pub use buttons::{Button, ButtonEvent};

use ::slint::{
    platform::software_renderer::{
        LineBufferProvider, MinimalSoftwareWindow, RepaintBufferType, TargetPixel,
    },
    ComponentHandle, Model, ModelRc, PhysicalSize, Timer, VecModel,
};

use crate::{
    device::{self, Device, Event},
    gameboy::Gameboy,
};

use self::slint::Argb1555;

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

pub struct UI {
    framebuffer: Vec<Argb1555>,
    window: Rc<MinimalSoftwareWindow>,
    event_queue: Receiver<Event>,
    root: slint::MainWindow,
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
            root,
        };
        ui.configure_root(device);
        ui
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

                // TODO don't do this every time
                let _ = line_buffer
                    .device
                    .fpga
                    .set_overlay_bounds(0x0, 0xFF, 0x0, 0x0, 0xFF, 0x0);
                line_buffer.device.set_brightness(u16::MAX / 4);
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

    fn rom_select_get_files(path: &Path) -> std::io::Result<Vec<String>> {
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

    fn configure_root(&self, device: &mut Device) {
        let backend = self.root.global::<slint::Backend>();
        let gameboy_ = Rc::new(RefCell::new(Gameboy::new()));

        self.root.global::<slint::Backend>().set_battery_level(
            device
                .fuel_gauge
                .get_battery_level()
                .map_or(0, |x| x.round() as i32),
        );

        let gameboy = gameboy_.clone();
        backend.on_main_menu_run_cartridge(move || {
            gameboy.borrow_mut().set_physical_cartridge().unwrap();
        });

        let rom_select_path: &Path = "/sdcard/roms".as_ref();
        let root = self.root.as_weak();
        backend.on_main_menu_load_rom(move || {
            let files = Self::rom_select_get_files(rom_select_path).unwrap();
            let files = ModelRc::from(Rc::new(VecModel::from(
                files.iter().map(|s| s.into()).collect::<Vec<_>>(),
            )));
            root.unwrap()
                .global::<slint::Backend>()
                .set_rom_select_list(files);
        });

        let root = self.root.as_weak();
        let gameboy = gameboy_.clone();
        backend.on_rom_select_selected(move |index| {
            let list = root
                .unwrap()
                .global::<slint::Backend>()
                .get_rom_select_list();
            if let Some(data) = list.row_data(index as usize) {
                let path = rom_select_path.join(data.as_str());
                log::info!("Selected ROM {}", path.display());

                match gameboy.borrow_mut().set_emulated_cartridge(path.as_path()) {
                    Ok(_) => true,
                    Err(e) => {
                        // TODO show an error message
                        log::error!("Error loading ROM: {:?}", e);
                        false
                    }
                }
            } else {
                false
            }
        });

        let gameboy = gameboy_.clone();
        backend.on_game_set_paused(move |paused| {
            let mut gameboy = gameboy.borrow_mut();
            gameboy.set_paused(paused).unwrap();
            if paused {
                // TODO handle error more gracefully
                gameboy.persist_ram().unwrap();
            }
        });

        let gameboy = gameboy_.clone();
        backend.on_game_reset(move || {
            gameboy.borrow_mut().reset().unwrap();
        });

        backend.on_screen_enter(|screen| {
            // Called when a new screen is entered, before the new frame is rendered.
            log::info!("Screen enter: {:?}", screen);
        })
    }
}
