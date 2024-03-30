use std::path::PathBuf;

use super::KvsKey;

/// The full path of the last selected ROM.
pub static LAST_ROM_PATH: KvsKey<PathBuf> = KvsKey::new("last-rom-path");

/// The last volume level.
pub static VOLUME: KvsKey<u8> = KvsKey::new_with_default("volume", 128);

/// The last brightness level.
pub static BRIGHTNESS: KvsKey<f32> = KvsKey::new_with_default("brightness", 0.50);

pub fn flush_all() {
    LAST_ROM_PATH.flush();
    VOLUME.flush();
    BRIGHTNESS.flush();
}
