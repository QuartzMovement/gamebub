use std::{borrow::Borrow, fmt::Display, io::Read, time::Duration};

use embedded_hal::{
    digital::{InputPin, OutputPin},
    spi::{Operation, SpiDevice},
};
use esp_idf_svc::hal::spi::{SpiDriver, SpiError, SpiSharedDeviceDriver};

#[derive(Debug)]
pub enum Error {
    PinError,
    ProgramError,
    BitstreamError,
    SpiError,
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
    Spi: SpiDevice,
> {
    pin_power: PinPower,
    pin_done: PinDone,
    pin_program_b: PinProgramB,
    pin_init_b: PinInitB,
    spi: Spi,
}

impl<PinPower, PinDone, PinProgramB, PinInitB, Spi>
    Fpga<PinPower, PinDone, PinProgramB, PinInitB, Spi>
where
    PinPower: OutputPin,
    PinDone: InputPin,
    PinProgramB: OutputPin,
    PinInitB: InputPin,
    Spi: SpiDevice,
{
    pub fn new(
        pin_power: PinPower,
        pin_done: PinDone,
        pin_program_b: PinProgramB,
        pin_init_b: PinInitB,
        spi: Spi,
    ) -> Self {
        Fpga {
            pin_power,
            pin_done,
            pin_program_b,
            pin_init_b,
            spi,
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

    const fn spi_command(
        read: bool,
        word_size: FpgaSpiWordSize,
        byte_swap: bool,
        auto_increment: bool,
    ) -> u8 {
        (read as u8)
            | ((word_size as u8) << 1)
            | ((byte_swap as u8) << 3)
            | ((auto_increment as u8) << 4)
    }

    fn spi_write(&mut self, command: u8, address: u32, data: &[u8]) -> Result<(), Error> {
        let address = address.to_be_bytes();
        self.spi
            .transaction(&mut [
                Operation::Write(&[command]),
                Operation::Write(&address),
                Operation::Write(&data),
            ])
            .map_err(|_| Error::SpiError)
    }

    fn spi_read(&mut self, command: u8, address: u32, buffer: &mut [u8]) -> Result<(), Error> {
        const DUMMY_BYTES: usize = 8;
        let address = address.to_be_bytes();
        let mut dummy = [0u8; DUMMY_BYTES];
        self.spi
            .transaction(&mut [
                Operation::Write(&[command]),
                Operation::Write(&address),
                Operation::Read(&mut dummy),
                Operation::Read(buffer),
            ])
            .map_err(|_| Error::SpiError)
    }

    pub fn write_u32(&mut self, address: u32, data: u32) -> Result<(), Error> {
        let command = Self::spi_command(false, FpgaSpiWordSize::Bits32, false, true);
        let data = data.to_be_bytes();
        self.spi_write(command, address, &data)
    }

    pub fn read_u32(&mut self, address: u32) -> Result<u32, Error> {
        let mut data = [0u8; 4];
        let command = Self::spi_command(true, FpgaSpiWordSize::Bits32, false, true);
        self.spi_read(command, address, &mut data)?;
        Ok(u32::from_le_bytes(data))
    }
}

#[derive(Copy, Clone)]
enum FpgaSpiWordSize {
    Bits8 = 0,
    Bits16 = 1,
    Bits32 = 2,
    Bits64 = 3,
}
