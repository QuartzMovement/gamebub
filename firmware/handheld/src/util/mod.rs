mod background_io;

pub use background_io::{BackgroundReader, ReaderResult};

use std::{fs::File, path::Path};

pub fn open_system_file(relative_path: &str) -> std::io::Result<File> {
    use std::io::{Error, ErrorKind};
    let roots = &[
        #[cfg(feature = "rev1")]
        "/sdcard/system_rev1/",
        #[cfg(feature = "rev2")]
        "/sdcard/system_rev2/",
        #[cfg(feature = "rev3")]
        "/sdcard/system_rev3/",
        "/sdcard/system/",
        "/system/",
    ];
    for root in roots {
        let path = Path::new(root).join(relative_path);
        log::info!("path: {}", path.display());
        match File::open(&path) {
            Ok(f) => return Ok(f),
            Err(e) if e.kind() == ErrorKind::NotFound => continue,
            Err(e) => return Err(e),
        }
    }
    Err(Error::from(ErrorKind::NotFound))
}

pub fn copy_file(from: &Path, to: &Path) -> std::io::Result<u64> {
    let mut reader = std::fs::File::open(from)?;
    // Workaround for an issue with esp-idf
    let metadata = reader.metadata()?.file_type();

    if !metadata.is_file() {
        return Err(std::io::Error::from(std::io::ErrorKind::InvalidInput));
    }

    let mut writer = std::fs::File::create(to)?;
    Ok(std::io::copy(&mut reader, &mut writer)?)
}
