use std::fs::File;
use std::{cell::RefCell, path::Path, rc::Rc, time::Duration};

use slint::private_unstable_api::re_exports as slint_re_exports;
use slint::{ComponentHandle, Global, Model, ModelRc, Timer, TimerMode, VecModel, Weak};

use crate::bitstream;
use crate::bitstream::gameboy::Gameboy;
use crate::{
    bitstream::gba::Gba,
    bitstream::Bitstream,
    device::{kvs, Device},
};
use flate2::read::GzDecoder;

use super::slint::{
    Backend, MainWindow, ScreenId, SettingDatetime, SettingEntry, SettingType, SettingValue,
};

mod settings;

enum CurrentBitstream {
    None,
    Gameboy(bitstream::gameboy::Gameboy),
    Gba(bitstream::gba::Gba),
}

pub struct UiState {
    root: Weak<MainWindow>,
    focus_stack: Vec<slint_re_exports::ItemWeak>,

    bitstream: CurrentBitstream,
    settings_model: Rc<settings::SettingsModel>,
}

impl UiState {
    pub fn new(root: &MainWindow, device: &mut Device) -> Rc<RefCell<Self>> {
        let state = UiState {
            root: root.as_weak(),
            focus_stack: Vec::new(),
            bitstream: CurrentBitstream::None,
            settings_model: Rc::new(settings::SettingsModel::new(device)),
        };
        let state = Rc::new(RefCell::new(state));
        state.borrow_mut().setup(state.clone(), device);
        state
    }

    pub fn update_battery_level(&mut self, device: &mut Device) {
        let level = device
            .fuel_gauge
            .get_battery_level()
            .map_or(0, |x| x.round() as i32);
        log::info!("Battery level: {:?}%", level);
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();
        backend.set_battery_level(level);
    }

    fn bitstream(&mut self) -> Option<&mut dyn Bitstream> {
        match &mut self.bitstream {
            CurrentBitstream::None => None,
            CurrentBitstream::Gameboy(x) => Some(x),
            CurrentBitstream::Gba(x) => Some(x),
        }
    }

    fn set_current_bitstream(&mut self, bitstream: CurrentBitstream) -> Result<(), String> {
        self.bitstream = bitstream;
        if let Some(bitstream) = self.bitstream() {
            Self::program_fpga(bitstream.get_bitstream_path());
            bitstream.on_after_program()?;
        }
        Ok(())
    }

    fn program_fpga(path: &str) {
        log::info!("Loading bitstream {}", path);
        let mut device = Device::lock();
        let file = File::open(path).unwrap();
        let mut bitstream = GzDecoder::new(file);
        device.lcd.enable_mcu_control().unwrap();
        device.fpga.program(&mut bitstream).unwrap();
        device.lcd.enable_fpga_control().unwrap();
        // TODO: re-render UI
    }

    /// Ensure a specific bitstream is loaded.
    fn ensure_bitstream_gameboy(&mut self) -> Result<(), String> {
        match &self.bitstream {
            CurrentBitstream::Gameboy(_) => Ok(()),
            _ => {
                let x = Gameboy::new();
                self.set_current_bitstream(CurrentBitstream::Gameboy(x))
            }
        }
    }

    fn ensure_bitstream_gba(&mut self) -> Result<(), String> {
        match &self.bitstream {
            CurrentBitstream::Gba(_) => Ok(()),
            _ => {
                let x = Gba::new();
                self.set_current_bitstream(CurrentBitstream::Gba(x))
            }
        }
    }

    /// Get the list of eligible files for the ROM select menu at the given directory
    fn rom_select_get_files(path: &Path) -> std::io::Result<Vec<String>> {
        let mut files = path
            .read_dir()?
            .filter_map(|e| {
                let e = e.ok()?;
                let name = e.file_name();
                let name = name.to_str()?;
                let kind = e.metadata().ok()?.file_type();
                if name.starts_with(".") {
                    return None;
                }
                let extensions = &[".gb", ".gbc", ".gba"];
                if kind.is_file() && !extensions.iter().any(|&ext| name.ends_with(ext)) {
                    return None;
                }
                Some((name.to_string(), kind))
            })
            .collect::<Vec<_>>();
        files.sort_unstable_by(|f1, f2| {
            // Sort by name, with directories first.
            let c1 = (f1.1.is_file(), f1.0.as_str());
            let c2 = (f2.1.is_file(), f2.0.as_str());
            c1.cmp(&c2)
        });
        let files = std::iter::once("..".to_string())
            .chain(files.into_iter().map(|f| f.0))
            .collect();
        Ok(files)
    }

    fn rom_select_update_list(&self, path: &Path) {
        let files = Self::rom_select_get_files(path).unwrap();
        let selected = kvs::keys::LAST_ROM_PATH
            .get()
            .and_then(|last_path| files.iter().position(|f| last_path == path.join(f)))
            .unwrap_or(0);
        let files = ModelRc::from(Rc::new(VecModel::from(
            files.iter().map(|s| s.into()).collect::<Vec<_>>(),
        )));

        let root = self.root.unwrap();
        let backend = Backend::get(&root);
        backend.set_rom_select_path(path.to_string_lossy().into_owned().into());
        backend.set_rom_select_list(files);
        backend.set_rom_select_initial(selected as i32);
    }

    fn setup(&mut self, state: Rc<RefCell<UiState>>, device: &mut Device) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();

        self.update_battery_level(device);
        backend.set_volume_level(((kvs::keys::VOLUME.get().unwrap() as i32) * 100) / 255);
        backend.set_brightness_level((kvs::keys::BRIGHTNESS.get().unwrap() * 100.0) as i32);

        let state_ = state.clone();
        backend.on_main_menu_run_cartridge(move || {
            let mut state = state_.borrow_mut();
            let cart_type = Device::lock().fpga.get_cartridge_slot_button().unwrap();
            log::info!("Cart button: {}", cart_type);
            let result = if cart_type {
                // Gameboy
                state.ensure_bitstream_gameboy()
            } else {
                // GBA
                state.ensure_bitstream_gba()
            };
            result.unwrap();

            match &mut state.bitstream {
                CurrentBitstream::None => unreachable!(),
                CurrentBitstream::Gameboy(x) => x.set_physical_cartridge().unwrap(),
                CurrentBitstream::Gba(x) => x.set_physical_cartridge().unwrap(),
            }
        });

        let rom_select_path: &Path = "/sdcard/roms".as_ref();
        let state_ = state.clone();
        backend.on_main_menu_load_rom(move || {
            state_.borrow().rom_select_update_list(rom_select_path);
        });

        let state_ = state.clone();
        backend.on_rom_select_selected(move |index| {
            let mut state = state_.borrow_mut();
            let list = Backend::get(&state.root.unwrap()).get_rom_select_list();
            if let Some(data) = list.row_data(index as usize) {
                let path = rom_select_path.join(data.as_str());
                log::info!("Selected ROM {}", path.display());
                kvs::keys::LAST_ROM_PATH.set(&path);

                if data.ends_with(".gbc") || data.ends_with(".gb") {
                    state.ensure_bitstream_gameboy().unwrap();
                } else if data.ends_with(".gba") {
                    state.ensure_bitstream_gba().unwrap();
                } else {
                    log::error!("Unsupported ROM file type");
                    return false;
                }

                match &mut state.bitstream {
                    CurrentBitstream::None => false,
                    CurrentBitstream::Gameboy(x) => {
                        match x.set_emulated_cartridge(path.as_path()) {
                            Ok(_) => true,
                            Err(e) => {
                                // TODO show an error message
                                log::error!("Error loading ROM: {:?}", e);
                                false
                            }
                        }
                    }
                    CurrentBitstream::Gba(x) => {
                        match x.set_emulated_cartridge(path.as_path()) {
                            Ok(_) => true,
                            Err(e) => {
                                // TODO show an error message
                                log::error!("Error loading ROM: {:?}", e);
                                false
                            }
                        }
                    }
                }
            } else {
                false
            }
        });

        let state_ = state.clone();
        backend.on_game_set_paused(move |paused| {
            let mut state = state_.borrow_mut();
            match &mut state.bitstream {
                CurrentBitstream::None => {}
                CurrentBitstream::Gameboy(x) => {
                    x.set_paused(paused).unwrap();
                    if paused {
                        // TODO handle error more gracefully
                        x.persist_ram().unwrap();
                    }
                }
                CurrentBitstream::Gba(x) => {
                    x.set_paused(paused).unwrap();
                    if paused {
                        x.persist_save().unwrap();
                    }
                }
            }
        });

        let state_ = state.clone();
        backend.on_game_reset(move || {
            let mut state = state_.borrow_mut();
            match &mut state.bitstream {
                CurrentBitstream::None => {}
                CurrentBitstream::Gameboy(x) => x.reset().unwrap(),
                CurrentBitstream::Gba(x) => x.reset().unwrap(),
            }
        });

        let state_ = state.clone();
        backend.on_screen_enter(move |screen| {
            let mut state = state_.borrow_mut();
            // Called when a new screen is entered, before the new frame is rendered.
            log::info!("Screen enter: {:?}", screen);
            match screen {
                ScreenId::Settings => state.on_settings_enter(),
                _ => {}
            }
        });

        backend.on_volume_changed({
            let state_ = state.clone();
            let timer = Timer::default();
            move |value| {
                let volume = ((value * (u8::MAX as i32)) / 100) as u8;
                Device::lock().dac.set_volume(volume).unwrap();
                // Start the timer to hide the bar.
                let state_ = state_.clone();
                timer.start(
                    TimerMode::SingleShot,
                    Duration::from_millis(1000),
                    move || {
                        kvs::keys::VOLUME.set(&volume);
                        Backend::get(&state_.borrow().root.unwrap()).set_volume_visible(false);
                    },
                )
            }
        });

        backend.on_brightness_changed({
            let state_ = state.clone();
            let timer = Timer::default();
            move |value| {
                let brightness = (value as f32) / 100.0;
                Device::lock().set_brightness(brightness);
                // Start the timer to hide the bar.
                let state_ = state_.clone();
                timer.start(
                    TimerMode::SingleShot,
                    Duration::from_millis(1000),
                    move || {
                        kvs::keys::BRIGHTNESS.set(&brightness);
                        Backend::get(&state_.borrow().root.unwrap()).set_brightness_visible(false);
                    },
                )
            }
        });

        backend.on_power_off(|| {
            Device::lock().power_off();
        });

        backend.on_reboot(|| {
            Device::lock().reboot();
        });

        let state_ = state.clone();
        backend.on_setting_changed(move |i, value| {
            state_
                .borrow_mut()
                .settings_model
                .changed(i as usize, value);
        });

        backend.on_tools_start_usb_drive(move || {
            crate::device::drivers::usb::setup_tinyusb().expect("USB setup failed");
        });
        backend.on_tools_end_usb_drive(move || {
            crate::device::drivers::usb::teardown_tinyusb().expect("USB teardown failed");
            Device::lock().reboot();
        });

        // Focus stack: allowing dialogs to push and pop focus.
        // Uses private, unstable APIs.
        let state_ = state.clone();
        backend.on_push_focus(move || {
            let mut state = state_.borrow_mut();
            let root = state.root.unwrap();
            let window_inner = slint_re_exports::WindowInner::from_pub(root.window());
            let item = window_inner.focus_item.borrow();
            state.focus_stack.push(item.clone());
        });
        let state_ = state.clone();
        backend.on_pop_focus(move || {
            let mut state = state_.borrow_mut();
            let root = state.root.unwrap();
            let window_inner = slint_re_exports::WindowInner::from_pub(root.window());
            while let Some(item) = state.focus_stack.pop() {
                if let Some(item) = item.upgrade() {
                    window_inner.set_focus_item(&item, true);
                    break;
                }
            }
        });

        // Utility function: add datetime with delta
        backend.on_datetime_add(settings::settings_datetime_add);
    }

    fn on_settings_enter(&mut self) {
        let root = self.root.unwrap();
        let backend = Backend::get(&root);
        self.settings_model.refresh();
        backend.set_settings(ModelRc::from(self.settings_model.clone()));
    }
}
