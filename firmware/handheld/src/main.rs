use std::fs::File;
use std::io::Read;
use std::sync::Mutex;
use std::time::Duration;

use esp_idf_svc::hal::gpio::*;
use esp_idf_svc::hal::i2c::*;
use esp_idf_svc::hal::ledc::{self, *};
use esp_idf_svc::hal::peripherals::Peripherals;
use esp_idf_svc::hal::spi::{self, *};
use esp_idf_svc::hal::units::FromValueType;
use flate2::read::GzDecoder;

mod dac;
mod fpga;
mod lcd;
mod sdcard;

fn main() -> anyhow::Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    let peripherals = Peripherals::take()?;
    let mut led = PinDriver::output(peripherals.pins.gpio3)?;

    // Turn LED off.
    led.set_low()?;
    log::info!("Booting handheld");

    let mut fpga_power = PinDriver::output(peripherals.pins.gpio46)?;
    fpga_power.set_high()?;

    let lcd_reset = PinDriver::output(peripherals.pins.gpio7)?;
    let lcd_dc = PinDriver::output(peripherals.pins.gpio16)?;
    let lcd_backlight = peripherals.pins.gpio6;
    let lcd_cs = peripherals.pins.gpio15;
    let dac_reset = PinDriver::output(peripherals.pins.gpio40)?;
    let spi_clk = peripherals.pins.gpio12;
    let spi_sdo = peripherals.pins.gpio11;
    let spi_sdi = peripherals.pins.gpio13;
    let i2c_scl = peripherals.pins.gpio39;
    let i2c_sda = peripherals.pins.gpio38;

    let i2c_config = I2cConfig::new().baudrate(400.kHz().into());
    let i2c = I2cDriver::new(peripherals.i2c0, i2c_sda, i2c_scl, &i2c_config)?;
    let i2c = Mutex::new(i2c);

    let spi_driver = SpiDriver::new(
        peripherals.spi2,
        spi_clk,
        spi_sdo,
        Some(spi_sdi),
        &SpiDriverConfig::new(),
    )?;

    let mut ledc_channel = LedcDriver::new(
        peripherals.ledc.channel0,
        LedcTimerDriver::new(
            peripherals.ledc.timer0,
            &ledc::config::TimerConfig::new().frequency(30.kHz().into()),
        )?,
        lcd_backlight,
    )?;
    ledc_channel.set_duty(ledc_channel.get_max_duty() / 4)?;

    // Setup LCD
    log::info!("Initializing LCD");
    let lcd_spi_config = spi::config::Config::new().baudrate(10.MHz().into());
    let lcd_spi_shared = Box::leak(Box::new(SpiSharedDeviceDriver::new(
        &spi_driver,
        &lcd_spi_config,
    )?));
    let lcd_spi = SpiSoftCsDeviceDriver::new(lcd_spi_shared, lcd_cs, Level::High)?;
    let mut lcd = lcd::ILI9488::new(lcd_reset, lcd_dc, lcd_spi);
    lcd.init()?;
    log::info!("Done initializing LCD");

    // Setup DAC
    log::info!("Initializing DAC");
    let mut dac = dac::TLV320DAC3101::new(dac_reset, embedded_hal_bus::i2c::MutexDevice::new(&i2c));
    dac.init()?;
    dac.set_volume(100)?;
    dac.set_mute(false)?;
    dac.set_headphones_enabled(true)?;
    dac.set_speakers_enabled(false)?;

    // Mount sdcard to /sdcard
    sdcard::mount_sdcard()?;
    let paths = std::fs::read_dir("/sdcard").unwrap();
    for path in paths {
        println!("sdcard: {}", path.unwrap().path().display())
    }

    // Program FPGA
    let fpga_pin_done = PinDriver::input(peripherals.pins.gpio17)?;
    let fpga_pin_program_b = PinDriver::output_od(peripherals.pins.gpio18)?;
    let fpga_pin_init_b = PinDriver::input(peripherals.pins.gpio8)?;
    let fpga_pin_spi_cs = peripherals.pins.gpio10;
    let fpga_spi_config = spi::config::Config::new().baudrate(32.MHz().into());
    let fpga_spi_shared = Box::leak(Box::new(SpiSharedDeviceDriver::new(
        &spi_driver,
        &fpga_spi_config,
    )?));
    let mut fpga_spi = SpiSoftCsDeviceDriver::new(fpga_spi_shared, fpga_pin_spi_cs, Level::High)?;
    fpga_spi.cs_pre_delay_us(100); // FPGA spi requires >35uS or so to stabilize after nCS.
    let mut fpga = fpga::Fpga::new(
        fpga_power,
        fpga_pin_done,
        fpga_pin_program_b,
        fpga_pin_init_b,
        fpga_spi,
    );

    let fpga_program_config = spi::config::Config::new().baudrate(80.MHz().into());
    let fpga_program_driver = SpiSharedDeviceDriver::new(&spi_driver, &fpga_program_config)?;
    {
        let mut bitstream = GzDecoder::new(File::open("/sdcard/top_handheld_cgb.bit.gz")?);
        fpga.program(&fpga_program_driver, &mut bitstream)?;
    }

    // testing
    {
        log::info!("transferring rom");
        let mut data = File::open("/sdcard/roms/Pokemon Silver.gbc")?;

        fpga.write_u32(0x0000_0000, 0b00)?;

        const CHUNK_SIZE: usize = 16 * 1024;
        let mut buf = vec![0; CHUNK_SIZE].into_boxed_slice();
        let mut total = 0u32;
        loop {
            let n = data.read(&mut buf)?;
            if n == 0 {
                break;
            }
            fpga.sdram_write(total, &buf[..n])?;
            total += n as u32;
        }

        // Take out of reset, leave paused.
        fpga.write_u32(0x0000_0000, 0b10)?;

        let emu_cart_config = 55;
        fpga.write_u32(0xC000_0000, emu_cart_config)?;
        fpga.write_u32(0xC000_0004, 0)?;
        fpga.write_u32(0xC000_0008, total - 1)?;
        fpga.write_u32(0xC000_000C, 0)?;
        fpga.write_u32(0xC000_0010, 0)?;

        log::info!("done transferring rom");
    }

    fpga.write_u32(0x0000_0000, 0b11)?;

    log::info!("Done, sleeping in a loop.");
    loop {
        std::thread::sleep(Duration::from_secs(1));
    }
}
