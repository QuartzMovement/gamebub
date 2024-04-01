use std::sync::{mpsc, MutexGuard};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use crate::ui::buttons::ButtonMap;
use embedded_hal::pwm::SetDutyCycle;
use embedded_hal_bus::i2c::MutexDevice as MutexI2C;
use esp_idf_svc::hal::gpio::{
    self, AnyIOPin, AnyInputPin, IOPin, Input, InputOutput, InputPin, OutputPin,
};
use esp_idf_svc::hal::gpio::{AnyOutputPin, Output, PinDriver};
use esp_idf_svc::hal::ledc::{LedcDriver, LedcTimerDriver};
use esp_idf_svc::hal::peripherals::Peripherals;
use esp_idf_svc::hal::spi::{
    self, SpiDeviceDriver, SpiDriver, SpiDriverConfig, SpiSharedDeviceDriver, SpiSoftCsDeviceDriver,
};
use esp_idf_svc::hal::units::FromValueType;
use esp_idf_svc::hal::{i2c::*, ledc};

pub mod drivers;
mod input;
mod interrupt;
pub mod kvs;

/// Time it may take for FPGA power rails to stabilize after enable.
/// TODO: actually measure this
const FPGA_POWER_DELAY: Duration = Duration::from_millis(100);

static DEVICE: OnceLock<Mutex<Device>> = OnceLock::new();

/// Main container for device hardware.
pub struct Device<'a> {
    /// Status led, active-high.
    #[allow(unused)]
    led: PinDriver<'a, AnyOutputPin, Output>,

    /// FPGA power in, active-high.
    fpga_power: PinDriver<'a, AnyOutputPin, Output>,

    /// The I2C bus.
    #[allow(unused)]
    i2c: &'a Mutex<I2cDriver<'a>>,

    /// LCD backlight PWM driver.
    lcd_backlight: LedcDriver<'a>,

    /// LCD driver
    pub lcd: drivers::lcd::ILI9488<
        PinDriver<'a, AnyOutputPin, Output>,
        PinDriver<'a, AnyOutputPin, Output>,
        SpiSoftCsDeviceDriver<'a, SpiSharedDeviceDriver<'a, &'a SpiDriver<'a>>, &'a SpiDriver<'a>>,
    >,

    /// DAC driver
    pub dac: drivers::dac::TLV320DAC3101<
        PinDriver<'a, AnyOutputPin, Output>,
        MutexI2C<'a, I2cDriver<'a>>,
    >,

    /// FPGA driver
    pub fpga: drivers::fpga::Fpga<
        PinDriver<'a, AnyInputPin, Input>,
        PinDriver<'a, AnyOutputPin, Output>,
        PinDriver<'a, AnyIOPin, Input>,
        SpiSoftCsDeviceDriver<'a, SpiSharedDeviceDriver<'a, &'a SpiDriver<'a>>, &'a SpiDriver<'a>>,
        SpiDeviceDriver<'a, &'a SpiDriver<'a>>,
    >,

    /// RTC driver
    pub rtc: drivers::rtc::PCF8563<MutexI2C<'a, I2cDriver<'a>>>,

    /// Battery fuel gauge driver
    pub fuel_gauge: drivers::fuel_gauge::MAX17048<MutexI2C<'a, I2cDriver<'a>>>,

    /// IMU driver
    pub imu: drivers::imu::LSM6DS3TRC<MutexI2C<'a, I2cDriver<'a>>>,

    io_expander: drivers::io_expander::TCA9535<MutexI2C<'a, I2cDriver<'a>>>,
    button_home: PinDriver<'a, AnyInputPin, Input>,
    button_vol_up: PinDriver<'a, AnyInputPin, Input>,
    button_vol_down: PinDriver<'a, AnyInputPin, Input>,
    button_power: PinDriver<'a, AnyIOPin, InputOutput>,
    pin_irq: PinDriver<'a, AnyInputPin, Input>,

    /// Event queue
    event_sender: mpsc::Sender<Event>,
    event_receiver: Option<mpsc::Receiver<Event>>,
}

impl Device<'_> {
    pub fn init() -> Result<(), anyhow::Error> {
        if let Some(_) = DEVICE.get() {
            panic!("Device already initialized.");
        }

        let peripherals = Peripherals::take()?;
        let pin_led = peripherals.pins.gpio3.downgrade_output();
        let pin_irq = peripherals.pins.gpio2.downgrade_input();
        let pin_home = peripherals.pins.gpio0.downgrade_input();
        let pin_vol_up = peripherals.pins.gpio4.downgrade_input();
        let pin_vol_down = peripherals.pins.gpio5.downgrade_input();
        let pin_power_switch = peripherals.pins.gpio1.downgrade();
        #[allow(unused)]
        let pin_vbus_pgood = peripherals.pins.gpio41.downgrade_input();
        #[allow(unused)]
        let pin_batt_chg = peripherals.pins.gpio42.downgrade_input();
        let pin_lcd_backlight = peripherals.pins.gpio6.downgrade_output();
        let pin_lcd_reset = peripherals.pins.gpio7.downgrade_output();
        let pin_lcd_cs = peripherals.pins.gpio15.downgrade_output();
        let pin_lcd_dc = peripherals.pins.gpio16.downgrade_output();
        let pin_fpga_power = peripherals.pins.gpio46.downgrade_output();
        let pin_fpga_init_b = peripherals.pins.gpio8.downgrade();
        let pin_fpga_done = peripherals.pins.gpio17.downgrade_input();
        let pin_fpga_program_b = peripherals.pins.gpio18.downgrade_output();
        let pin_fpga_spi_cs = peripherals.pins.gpio10.downgrade_output();
        let pin_spi_clk = peripherals.pins.gpio12.downgrade_output();
        let pin_spi_d0 = peripherals.pins.gpio11.downgrade();
        let pin_spi_d1 = peripherals.pins.gpio13.downgrade();
        #[allow(unused)]
        let pin_spi_d2 = peripherals.pins.gpio14.downgrade();
        #[allow(unused)]
        let pin_spi_d3 = peripherals.pins.gpio9.downgrade();
        let pin_i2c_scl = peripherals.pins.gpio39.downgrade();
        let pin_i2c_sda = peripherals.pins.gpio38.downgrade();
        let pin_sdio_clk = peripherals.pins.gpio45.downgrade_output();
        let pin_sdio_cmd = peripherals.pins.gpio48.downgrade();
        let pin_sdio_d0 = peripherals.pins.gpio35.downgrade();
        let pin_sdio_d1 = peripherals.pins.gpio36.downgrade();
        let pin_sdio_d2 = peripherals.pins.gpio21.downgrade();
        let pin_sdio_d3 = peripherals.pins.gpio47.downgrade();
        let pin_sd_detect = peripherals.pins.gpio37.downgrade_input();
        let pin_dac_reset = peripherals.pins.gpio40.downgrade_output();

        // Status LED
        let mut led = PinDriver::output(pin_led)?;
        led.set_low()?;

        kvs::Kvs::init()?;

        // TODO: see if we can avoid keeping FPGA power on all the time
        let mut fpga_power = PinDriver::output(pin_fpga_power)?;
        fpga_power.set_high()?;
        let fpga_power_time = Instant::now();

        // Initialize I2C
        // TODO: see if there's a good way to do this without making and leaking a Box
        let i2c_config = I2cConfig::new().baudrate(400.kHz().into());
        let i2c = I2cDriver::new(peripherals.i2c0, pin_i2c_sda, pin_i2c_scl, &i2c_config)?;
        let i2c = &*Box::leak(Box::new(Mutex::new(i2c)));

        let pin_irq = PinDriver::input(pin_irq)?;

        // LCD backlight
        let mut lcd_backlight = LedcDriver::new(
            peripherals.ledc.channel0,
            LedcTimerDriver::new(
                peripherals.ledc.timer0,
                &ledc::config::TimerConfig::new()
                    .frequency(25.kHz().into())
                    .resolution(ledc::config::Resolution::Bits11),
            )?,
            pin_lcd_backlight,
        )?;
        lcd_backlight.set_duty_cycle_fully_off().unwrap();

        // Setup SPI
        // TODO: see if there's a good way to do this without making and leaking a Box
        let spi_driver = &*Box::leak(Box::new(SpiDriver::new(
            peripherals.spi2,
            pin_spi_clk,
            pin_spi_d0,
            Some(pin_spi_d1),
            &SpiDriverConfig::new(),
        )?));

        // Setup LCD
        log::info!("Initializing LCD");
        let lcd_spi_config = spi::config::Config::new().baudrate(10.MHz().into());
        let lcd_spi = SpiSoftCsDeviceDriver::new(
            SpiSharedDeviceDriver::new(spi_driver, &lcd_spi_config)?,
            pin_lcd_cs,
            gpio::Level::High,
        )?;
        let lcd_reset = PinDriver::output(pin_lcd_reset)?;
        let lcd_dc = PinDriver::output(pin_lcd_dc)?;
        let mut lcd = drivers::lcd::ILI9488::new(lcd_reset, lcd_dc, lcd_spi);
        lcd.init()?;

        // Setup I/O expander
        let mut io_expander = drivers::io_expander::TCA9535::new(MutexI2C::new(&i2c));
        io_expander.get_pins()?;

        // Direct buttons
        let button_home = PinDriver::input(pin_home)?;
        let button_vol_up = PinDriver::input(pin_vol_up)?;
        let button_vol_down = PinDriver::input(pin_vol_down)?;
        let mut button_power = PinDriver::input_output_od(pin_power_switch)?;
        button_power.set_high()?;

        // Setup RTC
        let rtc = drivers::rtc::PCF8563::new(MutexI2C::new(&i2c));

        // Setup battery fuel gauge
        let fuel_gauge = drivers::fuel_gauge::MAX17048::new(MutexI2C::new(&i2c));

        // Setup IMU
        let mut imu = drivers::imu::LSM6DS3TRC::new(MutexI2C::new(&i2c));
        imu.init()?;

        // Ensure fpga power has stabilized.
        let time_since_fpga_power = Instant::now().duration_since(fpga_power_time);
        std::thread::sleep(FPGA_POWER_DELAY.saturating_sub(time_since_fpga_power));

        // Setup DAC (requires fpga_power on)
        log::info!("Initializing DAC");
        let dac_reset = PinDriver::output(pin_dac_reset)?;
        let mut dac = drivers::dac::TLV320DAC3101::new(dac_reset, MutexI2C::new(&i2c));
        dac.init()?;
        dac.set_volume(kvs::keys::VOLUME.get().unwrap())?;
        dac.set_mute(false)?;
        dac.set_headphones_enabled(true)?;
        dac.set_speakers_enabled(true)?;

        // Setup FPGA (without programming)
        let fpga_done = PinDriver::input(pin_fpga_done)?;
        let fpga_program_b = PinDriver::output_od(pin_fpga_program_b)?;
        let fpga_init_b = PinDriver::input(pin_fpga_init_b)?;
        let fpga_spi_config = spi::config::Config::new().baudrate(32.MHz().into());
        let mut fpga_spi = SpiSoftCsDeviceDriver::new(
            SpiSharedDeviceDriver::new(spi_driver, &fpga_spi_config)?,
            pin_fpga_spi_cs,
            gpio::Level::High,
        )?;
        fpga_spi.cs_pre_delay_us(100); // FPGA spi requires >35uS or so to stabilize after nCS.
        let fpga_program_config = spi::config::Config::new().baudrate(80.MHz().into());
        let fpga_program_spi = SpiDeviceDriver::new(
            spi_driver,
            Option::<AnyOutputPin>::None,
            &fpga_program_config,
        )?;
        let fpga = drivers::fpga::Fpga::new(
            fpga_done,
            fpga_program_b,
            fpga_init_b,
            fpga_spi,
            fpga_program_spi,
        );

        // Mount sdcard to /sdcard
        drivers::sdcard::mount_sdcard(
            "/sdcard",
            pin_sdio_clk,
            pin_sdio_cmd,
            pin_sdio_d0,
            pin_sdio_d1,
            pin_sdio_d2,
            pin_sdio_d3,
            Some(pin_sd_detect),
        )?;

        let (event_sender, event_receiver) = mpsc::channel();

        let mut device = Device {
            led,
            fpga_power,
            i2c,
            lcd_backlight,
            lcd,
            dac,
            fpga,
            fuel_gauge,
            io_expander,
            button_home,
            button_power,
            button_vol_up,
            button_vol_down,
            pin_irq,
            rtc,
            imu,
            event_sender,
            event_receiver: Some(event_receiver),
        };
        device.init_datetime();
        DEVICE
            .set(Mutex::new(device))
            .map_err(|_| ())
            .expect("Device already initialized");

        Device::setup_interrupts();

        Ok(())
    }

    pub fn get() -> &'static Mutex<Device<'static>> {
        DEVICE.get().unwrap()
    }

    pub fn lock() -> MutexGuard<'static, Device<'static>> {
        Device::get().lock().unwrap()
    }

    /// Enable or disable FPGA power.
    ///
    /// Note that it may take around 100ms to stabilize.
    pub fn set_fpga_power(&mut self, enable: bool) -> Result<(), anyhow::Error> {
        // TODO: maybe return a Future that completes after it's stable?
        self.fpga_power.set_level(enable.into())?;
        Ok(())
    }

    /// Display a framebuffer.
    ///
    /// Currently always an FPGA overlay.
    pub fn display_framebuffer_raw(&mut self, raw: &[u8]) {
        let _ = self.fpga.write_overlay(0, raw);
        let _ = self.fpga.set_overlay_bounds(0x0, 0xFF, 0x0, 0x0, 0xFF, 0x0);
    }

    /// Take the event queue receiver.
    pub fn take_event_receiver(&mut self) -> Option<mpsc::Receiver<Event>> {
        self.event_receiver.take()
    }

    /// Gracefully turn the device off.
    pub fn power_off(&mut self) -> ! {
        log::info!("Powering off");
        let _ = self.lcd_backlight.set_duty_cycle_fully_off();
        let _ = self.dac.reset();
        let _ = self.set_fpga_power(false);
        kvs::keys::flush_all();

        // Hold down the power button until the device shuts off.
        let _ = self.button_power.set_low();
        loop {
            std::thread::park();
        }
    }

    /// Set the LCD brightness. The input is a float in the range [0.0, 1.0].
    pub fn set_brightness(&mut self, brightness: f32) {
        // Brightness is perceived non-linearly -- 50% brightness is one step
        // less bright than 100%, 25% is one step less than 50%, etc.
        // Note that <1% duty cycle seems to be completely black. So we scale
        // brightness appropriately such that 0.0 maps to 1%.
        let max_duty = self.lcd_backlight.get_max_duty() as f32;
        let duty = ((0.99 * max_duty.powf(brightness)) + (0.01 * max_duty)) as u16;
        log::info!(
            "Setting LCD brightness to {} ({} / {})",
            brightness,
            duty,
            max_duty
        );
        self.lcd_backlight.set_duty_cycle(duty).unwrap();
    }

    /// Initialize the system time (after boot).
    ///
    /// Reads the time from the RTC. Sets a default time if no time is set.
    /// Then sets it in esp-idf (via libc settimeofday).
    fn init_datetime(&mut self) {
        if self.rtc.read_datetime().unwrap().is_none() {
            log::warn!("No date set, resetting");
            self.rtc
                .write_datetime(drivers::rtc::Datetime::default())
                .unwrap();
        }
        Device::set_esp_datetime(self.get_datetime());
    }

    /// Set the esp-idf system time.
    fn set_esp_datetime(dt: time::OffsetDateTime) {
        let timeval = esp_idf_svc::sys::timeval {
            tv_sec: dt.unix_timestamp(),
            tv_usec: 0,
        };
        unsafe {
            // SAFETY: this is safe to call with valid or null pointers.
            esp_idf_svc::sys::settimeofday(&timeval, std::ptr::null());
        }
    }

    /// Get the Device datetime.
    pub fn get_datetime(&mut self) -> time::OffsetDateTime {
        let rtc_time = self.rtc.read_datetime().unwrap();
        let ts = rtc_time
            .and_then(|dt| dt.as_timestamp())
            .unwrap_or(drivers::rtc::TIMESTAMP_2000);
        time::OffsetDateTime::from_unix_timestamp(ts as i64).unwrap()
    }

    /// Set the Device datetime.
    pub fn set_datetime(&mut self, dt: time::OffsetDateTime) {
        log::info!("Setting system time: {:?}", dt);
        Device::set_esp_datetime(dt);
        let ts = dt.unix_timestamp();
        let dt = drivers::rtc::Datetime::from_timestamp(ts as u64).unwrap_or_default();
        self.rtc.write_datetime(dt).unwrap();
    }
}

#[allow(unused)]
#[derive(Clone, Debug)]
pub enum Event {
    Button(ButtonMap),
    FpgaIrq,
}
