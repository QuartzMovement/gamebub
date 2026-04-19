use alloc::vec;
use ghostfat::GhostFat;
use usbd_scsi::BlockDevice as _;

use crate::flash::Flash;
use crate::usb_class::msc::BlockDevice;

pub const DISK_BLOCK_SIZE: u32 = 512;
pub const DISK_SIZE: u32 = 64 * 1024 * 1024;

pub struct Uf2VirtualDisk {
    #[allow(unused)]
    flash: &'static Flash,

    fat: GhostFat<'static>,
}

impl Uf2VirtualDisk {
    pub const BLOCK_SIZE: u32 = DISK_BLOCK_SIZE;

    pub fn new(flash: &'static Flash) -> Self {
        let serial = crate::info::SerialNumber::get();
        let hw = crate::info::HardwareVersion::get();
        let fw = env!("CARGO_PKG_VERSION");
        let info = alloc::format!(
            "Game Bub DFU {fw}\nModel: Game Bub Handheld\nBoard-ID: Game_Bub-Handheld-{hw}\nHardware Version: {hw}\nSerial: {serial}"
        );
        let info = info.leak().as_bytes();

        let files = vec![
            ghostfat::File::<512>::new(
                "INFO_UF2.txt",
                info,
            ).unwrap(),
            ghostfat::File::<512>::new(
                "INDEX.HTM",
                b"<html><head><meta http-equiv=\"refresh\" content=\"0;URL='https://docs.gamebub.net/?from=handheld-dfu'\"/></head><body>Redirecting to the <a href='https://docs.gamebub.net/'>Game Bub Documentation</a></body></html>",
            ).unwrap(),
        ];
        let mut config = ghostfat::Config::default();
        config.volume_label = "GAME BUB";
        config.num_blocks = DISK_SIZE / DISK_BLOCK_SIZE;
        let fat = ghostfat::GhostFat::new(files.leak(), config);

        Self { flash, fat }
    }
}

impl BlockDevice for Uf2VirtualDisk {
    type Error = usbd_scsi::BlockDeviceError;

    fn block_size(&self) -> u32 {
        DISK_BLOCK_SIZE
    }

    fn block_count(&self) -> u32 {
        self.fat.max_lba() + 1
    }

    fn read_block(&mut self, lba: u32, buf: &mut [u8]) -> Result<(), Self::Error> {
        self.fat.read_block(lba, buf)
    }

    fn write_block(&mut self, _lba: u32, _data: &[u8]) -> Result<(), Self::Error> {
        Ok(())
    }

    fn flush(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
}
