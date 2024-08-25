use std::ops::DerefMut;
use std::{cell::RefCell, path::Path, path::PathBuf, rc::Rc, time::Duration};

use slint::private_unstable_api::re_exports as slint_re_exports;
use slint::{ComponentHandle, Global, Model, ModelRc, Timer, TimerMode, VecModel, Weak};

use crate::bitstream::{self, CurrentBitstream};
use crate::worker;
use crate::{
    bitstream::Bitstream,
    device::{kvs, Device},
};

use super::slint::{
    Backend, MainWindow, ScreenId, SettingDatetime, SettingEntry, SettingType, SettingValue,
};

mod settings;

pub struct UiState {
    root: Weak<MainWindow>,
    focus_stack: Vec<slint_re_exports::ItemWeak>,

    rom_select_directory: PathBuf,
    settings_model: Rc<settings::SettingsModel>,
}

const ROM_SELECT_BASE_DIR: &str = "/sdcard/";

impl UiState {
    pub fn new(root: &MainWindow, device: &mut Device) -> Rc<RefCell<Self>> {
        let rom_select_directory = kvs::keys::LAST_ROM_PATH
            .get()
            .map(|mut p| {
                p.pop();
                p
            })
            .unwrap_or_else(|| Path::new(ROM_SELECT_BASE_DIR).to_path_buf());
        let state: UiState = UiState {
            root: root.as_weak(),
            focus_stack: Vec::new(),
            settings_model: Rc::new(settings::SettingsModel::new(device)),
            rom_select_directory,
        };
        let state = Rc::new(RefCell::new(state));
        state.borrow_mut().setup(state.clone(), device);
        state
    }

    pub fn update_battery_level(&mut self, level: f32) {
        let level = level.round() as i32;
        log::info!("Battery level: {:?}%", level);
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();
        backend.set_battery_level(level);
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
        let files = files.into_iter().map(|f| f.0).collect();
        Ok(files)
    }

    fn rom_select_update_list(&self) {
        let path = &self.rom_select_directory;
        let mut files = match Self::rom_select_get_files(path) {
            Ok(files) => files,
            Err(e) => {
                log::warn!("Error reading directory: {:?}", e);
                Vec::new()
            }
        };
        if path != Path::new(ROM_SELECT_BASE_DIR) {
            files.insert(0, "..".to_string());
        }

        // Determine initial selected file.
        // Note: this doesn't take effect during navigation, only when entering the screen.
        let selected = kvs::keys::LAST_ROM_PATH
            .get()
            .and_then(|last_path| files.iter().position(|f| last_path == path.join(f)))
            .unwrap_or(0);

        let files = ModelRc::from(Rc::new(VecModel::from(
            files.into_iter().map(|s| s.into()).collect::<Vec<_>>(),
        )));

        // Remove base directory from name before displaying.
        let mut directory = path
            .strip_prefix(ROM_SELECT_BASE_DIR)
            .unwrap_or(&path)
            .to_string_lossy()
            .into_owned();
        if !directory.starts_with("/") {
            directory.insert_str(0, "/");
        }

        let root = self.root.unwrap();
        let backend = Backend::get(&root);
        backend.set_rom_select_path(directory.into());
        backend.set_rom_select_list(files);
        backend.set_rom_select_initial(selected as i32);
    }

    /// Handle selection. Returns whether UI should adjust its focus item to position 0.
    fn rom_select_handle_select(&mut self, path: PathBuf, filename: &str) -> bool {
        if filename == ".." {
            if self.rom_select_directory == Path::new(ROM_SELECT_BASE_DIR) {
                log::warn!("No parent directory");
            } else {
                self.rom_select_directory.pop();
                self.rom_select_update_list();
            }
            return true;
        }
        if path.is_dir() {
            log::info!("Entering subdirectory {}", filename);
            self.rom_select_directory.push(filename);
            self.rom_select_update_list();
            return true;
        }

        log::info!("Selected ROM {}", path.display());
        worker::send(worker::Message::RunRomFile(path));
        false
    }

    fn setup(&mut self, state: Rc<RefCell<UiState>>, device: &mut Device) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();

        let battery_level = device.fuel_gauge.get_battery_level().unwrap_or(0.0);
        self.update_battery_level(battery_level);
        backend.set_volume_level(((kvs::keys::VOLUME.get().unwrap() as i32) * 100) / 255);
        backend.set_brightness_level((kvs::keys::BRIGHTNESS.get().unwrap() * 100.0) as i32);

        backend.on_main_menu_run_cartridge(|| worker::send(worker::Message::RunCartridge));

        let state_ = state.clone();
        backend.on_main_menu_load_rom(move || {
            let state = state_.borrow();
            state.rom_select_update_list();
        });

        let state_ = state.clone();
        backend.on_rom_select_selected(move |index| {
            let mut state = state_.borrow_mut();
            let list = Backend::get(&state.root.unwrap()).get_rom_select_list();
            if let Some(data) = list.row_data(index as usize) {
                let path = state.rom_select_directory.join(data.as_str());
                state.rom_select_handle_select(path, data.as_str())
            } else {
                false
            }
        });

        backend.on_game_set_paused(move |paused| {
            match bitstream::current().deref_mut() {
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

        backend.on_game_reset(move || match bitstream::current().deref_mut() {
            CurrentBitstream::None => {}
            CurrentBitstream::Gameboy(x) => x.reset().unwrap(),
            CurrentBitstream::Gba(x) => x.reset().unwrap(),
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
