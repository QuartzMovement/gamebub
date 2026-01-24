//! Worker threads to do background blocking work.

use std::ops::DerefMut;
use std::path::{Path, PathBuf};
use std::sync::{mpsc, OnceLock};

use crate::bitstream::CurrentBitstream;
use crate::device::drivers::{fpga, usb};
use crate::device::Device;
use crate::device::DisplayMode;
use crate::input::InputManager;
use crate::{bitstream, ui};

#[derive(Debug)]
pub enum Message {
    /// An interrupt request from the FPGA
    FpgaIrq(u32),
    /// The headphone state has changed
    HeadphoneState(bool),
    /// The docked state has changed
    DockState(bool),

    /// Run a cartridge
    RunCartridge,
    /// Persist emulated cartridge save
    SaveGame,
    /// Run a ROM file
    RunRomFile(PathBuf),
    /// Load ROM select entries
    ListRoms(PathBuf),
    /// Load Boot / Utility bitstream
    EnsureBootBitstream,

    /// Enable USB Serial/JTAG
    EnableUsbSerialJtag,
}

/// Send a message to the worker threads.
pub fn send(message: Message) {
    match SENDER.get() {
        Some(sender) => sender.send(message).unwrap(),
        None => log::error!("Dropping worker message {:?}", message),
    }
}

/// Start the worker threadpool. Called once during system init. Panics if called twice.
pub fn start() {
    let (sender, receiver) = mpsc::channel::<Message>();
    SENDER.set(sender).expect("Worker already initialized");

    std::thread::Builder::new()
        .name("Worker".to_string())
        .stack_size(8 * 1024)
        .spawn(move || {
            while let Ok(message) = receiver.recv() {
                log::debug!("Dispatch {:?}", message);
                dispatch(message);
            }
        })
        .unwrap();
}

static SENDER: OnceLock<mpsc::Sender<Message>> = OnceLock::new();

fn dispatch(message: Message) {
    match message {
        Message::FpgaIrq(irq_mask) => {
            if (irq_mask & fpga::Irq::ModuleVblank.as_flag()) != 0 {
                // Module vblank
                if let Some(bitstream) = crate::bitstream::current().get() {
                    bitstream.on_vblank_irq();
                }
            }
        }
        Message::HeadphoneState(has_headphones) => {
            log::info!("Headphone detection: {}", has_headphones);
            let mut device = Device::lock();
            device.dac.set_headphones_enabled(has_headphones).unwrap();
            device.dac.set_speakers_enabled(!has_headphones).unwrap();
        }
        Message::RunCartridge => {
            let cart_type = {
                let mut device = Device::lock();
                device.get_cart_switch()
            };
            log::info!("Cart switch: {}", cart_type);
            if cart_type {
                bitstream::current().ensure_gameboy().unwrap();
            } else {
                bitstream::current().ensure_gba().unwrap();
            };

            // Enable cartridge power after the bitstream is loaded.
            {
                let mut device = Device::lock();
                device.set_cart_power(true);
            }

            match bitstream::current().deref_mut() {
                CurrentBitstream::None => unreachable!(),
                CurrentBitstream::Gameboy(x) => x.set_physical_cartridge().unwrap(),
                CurrentBitstream::Gba(x) => x.set_physical_cartridge().unwrap(),
            }

            ui::send(ui::Message::EnterGame);
        }
        Message::SaveGame => {
            // TODO handle error more gracefully
            match bitstream::current().deref_mut() {
                CurrentBitstream::None => {}
                CurrentBitstream::Gameboy(x) => x.persist_ram().unwrap(),
                CurrentBitstream::Gba(x) => x.persist_save().unwrap(),
            }
            ui::send(ui::Message::GameSaved);
        }
        Message::RunRomFile(path) => {
            match path.extension().and_then(|e| e.to_str()) {
                Some("gbc") | Some("gb") => bitstream::current().ensure_gameboy().unwrap(),
                Some("gba") => bitstream::current().ensure_gba().unwrap(),
                _ => {
                    ui::send(ui::Message::RomSelectError(
                        "unsupported ROM file type".into(),
                    ));
                    return;
                }
            }

            let result: Result<(), String> = match bitstream::current().deref_mut() {
                CurrentBitstream::None => Err("no bitstream".into()),
                CurrentBitstream::Gameboy(x) => x
                    .set_emulated_cartridge(path.as_path())
                    .map_err(|e| e.to_string()),
                CurrentBitstream::Gba(x) => x
                    .set_emulated_cartridge(path.as_path())
                    .map_err(|e| e.to_string()),
            };
            match result {
                Ok(()) => ui::send(ui::Message::EnterGame),
                Err(err) => {
                    bitstream::current().ensure_boot().unwrap();
                    ui::send(ui::Message::RomSelectError(err))
                }
            }
        }
        Message::ListRoms(path) => {
            let files = match rom_select_get_files(&path) {
                Ok(files) => files,
                Err(e) => {
                    log::warn!("Error listing directory: {:?}", e);
                    ui::send(ui::Message::RomSelectError(format!(
                        "Error listing directory:\n{}",
                        e,
                    )));
                    Vec::new()
                }
            };
            ui::send(ui::Message::RomSelectFiles(files))
        }
        Message::DockState(docked) => {
            // Either we undocked (so remove all gamepads), or we just docked and we should ensure a clean state.
            InputManager::lock().remove_all_gamepads();

            let mode = if docked {
                DisplayMode::External
            } else {
                DisplayMode::Internal
            };
            let mut device = Device::lock();
            device.docked = docked;
            device.change_display_mode(mode).unwrap();
        }
        Message::EnsureBootBitstream => {
            bitstream::current().ensure_boot().unwrap();
        }
        Message::EnableUsbSerialJtag => {
            if let Err(e) = usb::configure_usb(usb::UsbMode::SerialJtag) {
                log::error!("Failed to configure USB Serial/JTAG: {e}");
            }
        }
        #[allow(unreachable_patterns)]
        _ => {
            log::warn!("Unhandled message: {:?}", message);
        }
    }
}

/// Get the list of eligible files for the ROM select menu at the given directory
fn rom_select_get_files(path: &Path) -> std::io::Result<Vec<(String, bool)>> {
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
    let files = files.into_iter().map(|f| (f.0, f.1.is_dir())).collect();
    Ok(files)
}
