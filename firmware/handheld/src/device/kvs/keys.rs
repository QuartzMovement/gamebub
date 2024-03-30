use std::path::PathBuf;

use super::KvsKey;

/// The full path of the last selected ROM.
pub static LAST_ROM_PATH: KvsKey<PathBuf> = KvsKey::new("last-rom-path", false);

pub fn flush_all() {
    LAST_ROM_PATH.flush();
}
