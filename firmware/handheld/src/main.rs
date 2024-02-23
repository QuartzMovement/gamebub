use std::fs::File;
use std::io::Read;

use esp_idf_svc::hal::peripherals::Peripherals;
use flate2::read::GzDecoder;

use device::Device;

mod device;

fn main() -> anyhow::Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    log::info!("Initializing device");
    let peripherals = Peripherals::take()?;
    let mut device = Device::init(peripherals)?;

    let paths = std::fs::read_dir("/sdcard").unwrap();
    for path in paths {
        println!("sdcard: {}", path.unwrap().path().display())
    }

    // Test RTC
    let datetime = device.rtc.read_datetime()?;
    match datetime {
        Some(datetime) => log::info!("Current datetime: {:?}", datetime),
        None => {
            log::info!("No date set, resetting");
            device
                .rtc
                .write_datetime(device::rtc::Datetime::default())?;
        }
    }

    // Program FPGA
    {
        let mut bitstream = GzDecoder::new(File::open("/sdcard/top_handheld_cgb.bit.gz")?);
        device.fpga.program(&mut bitstream)?;
    }

    // testing
    {
        log::info!("transferring rom");
        let mut data = File::open("/sdcard/roms/Pokemon Silver.gbc")?;

        device.fpga.write_u32(0x0000_0000, 0b00)?;

        const CHUNK_SIZE: usize = 16 * 1024;
        let mut buf = vec![0; CHUNK_SIZE].into_boxed_slice();
        let mut total = 0u32;
        loop {
            let n = data.read(&mut buf)?;
            if n == 0 {
                break;
            }
            device.fpga.sdram_write(total, &buf[..n])?;
            total += n as u32;
        }

        // Take out of reset, leave paused.
        device.fpga.write_u32(0x0000_0000, 0b10)?;

        let emu_cart_config = 55;
        device.fpga.write_u32(0xC000_0000, emu_cart_config)?;
        device.fpga.write_u32(0xC000_0004, 0)?;
        device.fpga.write_u32(0xC000_0008, total - 1)?;
        device.fpga.write_u32(0xC000_000C, 0)?;
        device.fpga.write_u32(0xC000_0010, 0)?;

        log::info!("done transferring rom");
    }

    device.fpga.write_u32(0x0000_0000, 0b11)?;

    log::info!("Done");
    loop {
        std::thread::park();
    }
}
