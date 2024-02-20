use std::{borrow::Borrow, fmt::Display, io::Read, time::Duration};

use embedded_hal::{
    digital::{InputPin, OutputPin},
    spi::SpiDevice,
};
use esp_idf_svc::hal::spi::{SpiDriver, SpiSharedDeviceDriver};

#[derive(Debug)]
pub enum Error {
    PinError,
    ProgramError,
    BitstreamError,
}

impl Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl std::error::Error for Error {}

pub struct Fpga<
    PinPower: OutputPin,
    PinDone: InputPin,
    PinProgramB: OutputPin,
    PinInitB: InputPin,
    PinSpiCs: OutputPin,
    // Spi: SpiDevice,
> {
    pin_power: PinPower,
    pin_done: PinDone,
    pin_program_b: PinProgramB,
    pin_init_b: PinInitB,
    pin_spi_cs: PinSpiCs,
    // spi: Spi,
}

impl<PinPower, PinDone, PinProgramB, PinInitB, PinSpiCs>
    Fpga<PinPower, PinDone, PinProgramB, PinInitB, PinSpiCs>
where
    PinPower: OutputPin,
    PinDone: InputPin,
    PinProgramB: OutputPin,
    PinInitB: InputPin,
    PinSpiCs: OutputPin,
    // Spi: SpiDevice,
{
    pub fn new(
        pin_power: PinPower,
        pin_done: PinDone,
        pin_program_b: PinProgramB,
        pin_init_b: PinInitB,
        pin_spi_cs: PinSpiCs,
        // spi: Spi,
    ) -> Self {
        Fpga {
            pin_power,
            pin_done,
            pin_program_b,
            pin_init_b,
            pin_spi_cs,
            // spi,
        }
    }

    // SpiSharedDeviceDriver
    pub fn program<'d, Driver>(
        &mut self,
        spi: &SpiSharedDeviceDriver<'d, Driver>,
        bitstream: &mut dyn Read,
    ) -> Result<(), Error>
    where
        Driver: Borrow<SpiDriver<'d>> + 'd,
    {
        self.pin_power.set_high().map_err(|_| Error::PinError)?;

        // Wait 100ms after power on.
        std::thread::sleep(Duration::from_millis(100));

        spi.lock(|spi| {
            // Pull PROGRAM_B low, hold it for at least 250ns.
            self.pin_program_b.set_low().map_err(|_| Error::PinError)?;
            std::thread::sleep(Duration::from_millis(1));
            if self.pin_init_b.is_high().map_err(|_| Error::PinError)? {
                return Err(Error::ProgramError);
            }
            self.pin_program_b.set_high().map_err(|_| Error::PinError)?;

            // INIT_B will go high at most 5ms after PROGRAM_B release.
            std::thread::sleep(Duration::from_millis(5));
            if self.pin_init_b.is_low().map_err(|_| Error::PinError)? {
                return Err(Error::ProgramError);
            }

            log::info!("FPGA is in program mode");

            let mut bitstream_header = [0u8; 129];
            bitstream
                .read(&mut bitstream_header)
                .map_err(|_| Error::BitstreamError)?;

            const CHUNK_SIZE: usize = 16 * 1024;
            let mut buf = vec![0; CHUNK_SIZE].into_boxed_slice();
            loop {
                let n = bitstream
                    .read(&mut buf)
                    .map_err(|_| Error::BitstreamError)?;
                if n == 0 {
                    break;
                }
                spi.write(&buf[..n]).map_err(|_| Error::ProgramError)?;
            }

            log::info!(
                "Programmed FPGA, done={}",
                self.pin_done.is_high().map_err(|_| Error::PinError)?
            );

            Ok(())
        })
    }
}
