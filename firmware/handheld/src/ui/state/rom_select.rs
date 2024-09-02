use std::{
    cell::RefCell,
    path::{Path, PathBuf},
    rc::Rc,
};

use super::super::slint::Backend;
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::{device::Device, kvs, worker};

use super::UiState;

pub const BASE_DIR: &str = "/sdcard/";

impl UiState {
    /// Set up the "Rom Select" screen.
    pub(super) fn setup_rom_select(&mut self, state: &Rc<RefCell<UiState>>, _device: &mut Device) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();

        let state_ = state.clone();
        backend.on_rom_select_selected(move |index| {
            let mut state = state_.borrow_mut();
            let root = state.root.unwrap();
            let backend = root.global::<Backend>();
            let list = backend.get_rom_select_list();
            if let Some(data) = list.row_data(index as usize) {
                let path = state.rom_select_directory.join(data.as_str());
                state.rom_select_handle_select(path, data.as_str())
            } else {
                false
            }
        });

        let state_ = state.clone();
        backend.on_rom_select_up(move || {
            let mut state = state_.borrow_mut();
            state.rom_select_handle_select(PathBuf::new(), "..")
        });
    }

    pub fn rom_select_update_list(&mut self, mut files: Vec<String>) {
        let path = &self.rom_select_directory;
        if path != Path::new(BASE_DIR) {
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
            .strip_prefix(BASE_DIR)
            .unwrap_or(&path)
            .to_string_lossy()
            .into_owned();
        if !directory.starts_with("/") {
            directory.insert_str(0, "/");
        }

        let root = self.root.unwrap();
        let backend = root.global::<Backend>();
        backend.set_rom_select_path(directory.into());
        backend.set_rom_select_list(files);
        backend.set_rom_select_index(selected as i32);
        backend.set_rom_select_is_loading(false);
    }

    /// Handle selection. Returns whether a loading screen should be displayed.
    fn rom_select_handle_select(&mut self, path: PathBuf, filename: &str) -> bool {
        if filename == ".." {
            if self.rom_select_directory == Path::new(BASE_DIR) {
                log::warn!("No parent directory");
                return false;
            } else {
                self.rom_select_directory.pop();
                worker::send(worker::Message::ListRoms(self.rom_select_directory.clone()));
            }
        } else if path.is_dir() {
            log::info!("Entering subdirectory {}", filename);
            self.rom_select_directory.push(filename);
            worker::send(worker::Message::ListRoms(self.rom_select_directory.clone()));
        } else {
            log::info!("Selected ROM {}", path.display());
            worker::send(worker::Message::RunRomFile(path));
        }
        self.root
            .unwrap()
            .global::<Backend>()
            .set_rom_select_progress(0.0);
        true
    }
}
