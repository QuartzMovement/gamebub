use std::{cell::RefCell, path::Path, rc::Rc, time::Duration};

use slint::private_unstable_api::re_exports as slint_re_exports;
use slint::{ComponentHandle, Global, Model, ModelRc, Timer, TimerMode, VecModel, Weak};

use crate::{
    device::{kvs, Device},
    gameboy::Gameboy,
};

use super::slint::{
    Backend, MainWindow, ScreenId, SettingDatetime, SettingEntry, SettingType, SettingValue,
};

mod settings;

pub struct UiState {
    root: Weak<MainWindow>,
    focus_stack: Vec<slint_re_exports::ItemWeak>,

    pub gameboy: Gameboy,
    settings_model: Rc<settings::SettingsModel>,
}

impl UiState {
    pub fn new(root: &MainWindow, device: &mut Device) -> Rc<RefCell<Self>> {
        let state = UiState {
            root: root.as_weak(),
            focus_stack: Vec::new(),
            gameboy: Gameboy::new(),
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

    fn setup(&mut self, state: Rc<RefCell<UiState>>, device: &mut Device) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();

        self.update_battery_level(device);
        backend.set_volume_level(((kvs::keys::VOLUME.get().unwrap() as i32) * 100) / 255);
        backend.set_brightness_level((kvs::keys::BRIGHTNESS.get().unwrap() * 100.0) as i32);

        let state_ = state.clone();
        backend.on_main_menu_run_cartridge(move || {
            state_
                .borrow_mut()
                .gameboy
                .set_physical_cartridge()
                .unwrap();
        });

        let rom_select_path: &Path = "/sdcard/roms".as_ref();
        let state_ = state.clone();
        backend.on_main_menu_load_rom(move || {
            let files = Self::rom_select_get_files(rom_select_path).unwrap();
            let selected = kvs::keys::LAST_ROM_PATH
                .get()
                .and_then(|last_path| {
                    files
                        .iter()
                        .position(|f| last_path == rom_select_path.join(f))
                })
                .unwrap_or(0);
            let files = ModelRc::from(Rc::new(VecModel::from(
                files.iter().map(|s| s.into()).collect::<Vec<_>>(),
            )));

            let root = state_.borrow().root.unwrap();
            let backend = Backend::get(&root);
            backend.set_rom_select_list(files);
            backend.set_rom_select_initial(selected as i32);
        });

        let state_ = state.clone();
        backend.on_rom_select_selected(move |index| {
            let mut state = state_.borrow_mut();
            let list = Backend::get(&state.root.unwrap()).get_rom_select_list();
            if let Some(data) = list.row_data(index as usize) {
                let path = rom_select_path.join(data.as_str());
                log::info!("Selected ROM {}", path.display());
                kvs::keys::LAST_ROM_PATH.set(&path);

                match state.gameboy.set_emulated_cartridge(path.as_path()) {
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

        let state_ = state.clone();
        backend.on_game_set_paused(move |paused| {
            let mut state = state_.borrow_mut();
            state.gameboy.set_paused(paused).unwrap();
            if paused {
                // TODO handle error more gracefully
                state.gameboy.persist_ram().unwrap();
            }
        });

        let state_ = state.clone();
        backend.on_game_reset(move || {
            state_.borrow_mut().gameboy.reset().unwrap();
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

        let state_ = state.clone();
        backend.on_setting_changed(move |i, value| {
            state_
                .borrow_mut()
                .settings_model
                .changed(i as usize, value);
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
                    window_inner.set_focus_item(&item);
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
