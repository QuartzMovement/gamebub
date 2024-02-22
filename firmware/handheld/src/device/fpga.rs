use std::{fmt::Display, io::Read, time::Duration};

use embedded_hal::{
    digital::{InputPin, OutputPin},
    spi::{Operation, SpiDevice},
};

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
    PinDone: InputPin,
    PinProgramB: OutputPin,
    PinInitB: InputPin,
    Spi: SpiDevice,
    ProgramSpi: SpiDevice,
> {
    pin_done: PinDone,
    pin_program_b: PinProgramB,
    pin_init_b: PinInitB,
    spi: Spi,
    program_spi: ProgramSpi,
}

impl<PinDone, PinProgramB, PinInitB, Spi, ProgramSpi>
    Fpga<PinDone, PinProgramB, PinInitB, Spi, ProgramSpi>
where
    PinDone: InputPin,
    PinProgramB: OutputPin,
    PinInitB: InputPin,
    Spi: SpiDevice,
    ProgramSpi: SpiDevice,
{
    pub fn new(
        pin_done: PinDone,
        pin_program_b: PinProgramB,
        pin_init_b: PinInitB,
        spi: Spi,
        program_spi: ProgramSpi,
    ) -> Self {
        Fpga {
            pin_done,
            pin_program_b,
            pin_init_b,
            spi,
            program_spi,
        }
    }

    /// Program the FPGA with a new bitstream.
    pub fn program(&mut self, bitstream: &mut dyn Read) -> Result<(), Error> {
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
            self.program_spi
                .write(&buf[..n])
                .map_err(|_| Error::ProgramError)?;
        }

        log::info!(
            "Programmed FPGA, done={}",
            self.pin_done.is_high().map_err(|_| Error::PinError)?
        );

        Ok(())
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
        Ok(u32::from_be_bytes(data))
    }

    pub fn sram_write(&mut self, address: u32, data: &[u8]) -> Result<(), Error> {
        let address = 0x1000_0000 | address;
        let command = Self::spi_command(false, FpgaSpiWordSize::Bits16, true, true);
        self.spi_write(command, address, data)
    }

    pub fn sram_read(&mut self, address: u32, data: &mut [u8]) -> Result<(), Error> {
        let address = 0x1000_0000 | address;
        let command = Self::spi_command(true, FpgaSpiWordSize::Bits16, true, true);
        self.spi_read(command, address, data)
    }

    pub fn sdram_write(&mut self, address: u32, data: &[u8]) -> Result<(), Error> {
        let address = 0x2000_0000 | address;
        let command = Self::spi_command(false, FpgaSpiWordSize::Bits32, true, true);
        self.spi_write(command, address, data)
    }

    pub fn sdram_read(&mut self, address: u32, data: &mut [u8]) -> Result<(), Error> {
        let address = 0x2000_0000 | address;
        let command = Self::spi_command(true, FpgaSpiWordSize::Bits32, true, true);
        self.spi_read(command, address, data)
    }

    pub fn show_overlay(
        &mut self,
        start_x: u8,
        end_x: u8,
        scroll_x: u8,
        start_y: u8,
        end_y: u8,
        scroll_y: u8,
    ) -> Result<(), Error> {
        let config_x = ((start_x as u32) & 0xFF) << 16
            | ((end_x as u32) & 0xFF) << 8
            | ((scroll_x as u32) & 0xFF);
        let config_y = ((start_y as u32) & 0xFF) << 16
            | ((end_y as u32) & 0xFF) << 8
            | ((scroll_y as u32) & 0xFF);
        self.write_u32(0x100, config_x)?;
        self.write_u32(0x104, config_y)?;
        Ok(())
    }

    pub fn hide_overlay(&mut self) -> Result<(), Error> {
        self.show_overlay(0, 0, 0, 0, 0, 0)
    }
}

#[allow(unused)]
#[derive(Copy, Clone)]
enum FpgaSpiWordSize {
    Bits8 = 0,
    Bits16 = 1,
    Bits32 = 2,
    Bits64 = 3,
}
