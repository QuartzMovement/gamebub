#![allow(dead_code)]

use embedded_hal::i2c::I2c;
use std::time::Duration;
use thiserror::Error;

const ADDRESS: u8 = 0x55;
/// Wait time after each I2C transaction before the next
const WAIT_TIME: Duration = Duration::from_micros(66);
/// Expected chip ID
const CHIP_ID: u16 = 0x0427;

mod command {
    pub struct Standard(pub u8);
    pub struct Control(pub u16);

    pub const CONTROL: Standard = Standard(0x00);
    pub const TEMPERATURE: Standard = Standard(0x02);
    pub const VOLTAGE: Standard = Standard(0x04);
    pub const FLAGS: Standard = Standard(0x06);
    pub const NOMINAL_AVAILABLE_CAPACITY: Standard = Standard(0x08);
    pub const FULL_AVAILABLE_CAPACITY: Standard = Standard(0x0A);
    pub const REMAINING_CAPACITY: Standard = Standard(0x0C);
    pub const FULL_CHARGE_CAPACITY: Standard = Standard(0x0E);
    pub const AVERAGE_CURRENT: Standard = Standard(0x10);
    pub const AVERAGE_POWER: Standard = Standard(0x18);
    pub const STATE_OF_CHARGE: Standard = Standard(0x1C);
    pub const INTERNAL_TEMPERATURE: Standard = Standard(0x1E);
    pub const STATE_OF_HEALTH: Standard = Standard(0x20);
    pub const REMAINING_CAPACITY_UNFILTERED: Standard = Standard(0x28);
    pub const REMAINING_CAPACITY_FILTERED: Standard = Standard(0x2A);
    pub const FULL_CHARGE_CAPACITY_UNFILTERED: Standard = Standard(0x2C);
    pub const FULL_CHARGE_CAPACITY_FILTERED: Standard = Standard(0x2E);
    pub const STATE_OF_CHARGE_UNFILTERED: Standard = Standard(0x30);

    pub const CONTROL_STATUS: Control = Control(0x0000);
    pub const DEVICE_TYPE: Control = Control(0x0001);
    pub const FW_VERSION: Control = Control(0x0002);
    pub const DM_CODE: Control = Control(0x0004);
    pub const PREV_MACWRITE: Control = Control(0x0007);
    pub const CHEM_ID: Control = Control(0x0008);
    pub const BAT_INSERT: Control = Control(0x000C);
    pub const BAT_REMOVE: Control = Control(0x000D);
    pub const SET_CFGUPDATE: Control = Control(0x0013);
    pub const SMOOTH_SYNC: Control = Control(0x0019);
    pub const SHUTDOWN_ENABLE: Control = Control(0x001B);
    pub const SHUTDOWN: Control = Control(0x001C);
    pub const SEALED: Control = Control(0x0020);
    pub const PULSE_SOC_INT: Control = Control(0x0023);
    pub const CHEM_A: Control = Control(0x0030);
    pub const CHEM_B: Control = Control(0x0031);
    pub const CHEM_C: Control = Control(0x0032);
    pub const RESET: Control = Control(0x0041);
    pub const SOFT_RESET: Control = Control(0x0042);
}

/// Bit flags returned by the Flags command
mod flag {
    pub const OT: u16 = 1 << 15;
    pub const UT: u16 = 1 << 14;
    pub const FC: u16 = 1 << 9;
    pub const CHG: u16 = 1 << 8;
    pub const OCVTAKEN: u16 = 1 << 7;
    pub const DOD_CORRECT: u16 = 1 << 6;
    pub const ITPOR: u16 = 1 << 5;
    pub const CFGUPMODE: u16 = 1 << 4;
    pub const BAT_DET: u16 = 1 << 3;
    pub const SOC1: u16 = 1 << 2;
    pub const SOCF: u16 = 1 << 1;
    pub const DSG: u16 = 1 << 0;
}

// Bit flags returned by the ControlStatus command
mod control_status {
    pub const SHUTDOWNEN: u16 = 1 << 15;
    pub const WDRESET: u16 = 1 << 14;
    pub const SS: u16 = 1 << 13;
    pub const CALMODE: u16 = 1 << 12;
    pub const CCA: u16 = 1 << 11;
    pub const BCA: u16 = 1 << 10;
    pub const QMAX_UP: u16 = 1 << 9;
    pub const RES_UP: u16 = 1 << 8;
    pub const INITCOMP: u16 = 1 << 7;
    pub const SLEEP: u16 = 1 << 4;
    pub const LDMD: u16 = 1 << 3;
    pub const RUP_DIS: u16 = 1 << 2;
    pub const VOK: u16 = 1 << 1;
    pub const CHEM_CHANGE: u16 = 1 << 0;
}

#[derive(Debug, Error)]
pub enum Error {
    #[error("i2c error")]
    I2cError,
    #[error("poll timeout")]
    Timeout,
    #[error("chip id")]
    ChipId,
}

pub struct BQ27427<I2C: I2c> {
    i2c: I2C,
}

impl<I2C> BQ27427<I2C>
where
    I2C: I2c,
{
    pub fn new(i2c: I2C) -> Self {
        BQ27427 { i2c }
    }

    /// Configure the fuel gauge (blocking, may take a while)
    pub fn configure(&mut self, force: bool) -> Result<(), Error> {
        let chip_id = self.read_control(command::DEVICE_TYPE)?;
        if chip_id != CHIP_ID {
            return Err(Error::ChipId);
        }

        let flags = self.get_flags()?;
        if force {
            log::info!("Forcing fuel gauge reconfigure");
        } else if (flags & flag::ITPOR) != 0 {
            log::info!("Fuel gauge needs configure");
        } else {
            log::info!("Fuel gauge already configured");
            return Ok(());
        }

        // TODO: unseal and re-seal?

        // Enter CFGUPDATE mode.
        self.write_control(command::SET_CFGUPDATE)?;
        self.poll_for_flag(flag::CFGUPMODE)?;

        // Set battery chemistry B (1202), 4.2V
        self.write_control(command::CHEM_B)?;

        // TODO: design capacity and other parameters

        self.soft_reset()?;
        log::info!("Configured fuel gauge");
        Ok(())
    }

    fn poll_for_flag(&mut self, flag: u16) -> Result<(), Error> {
        let retries = 10;
        let delay = Duration::from_millis(500);
        for _ in 0..retries {
            std::thread::sleep(delay);

            if (self.get_flags()? & flag) != 0 {
                return Ok(());
            }
        }
        Err(Error::Timeout)
    }

    /// Hard reset the chip, clearing memory
    pub fn hard_reset(&mut self) -> Result<(), Error> {
        self.write_control(command::RESET)
    }

    /// Soft reset the chip
    pub fn soft_reset(&mut self) -> Result<(), Error> {
        self.write_control(command::SOFT_RESET)
    }

    /// Read the temperature in Kelvin.
    ///
    /// Internal, external, or manual depending on `OpConfig[TEMPS]``.
    pub fn get_temperature(&mut self) -> Result<f32, Error> {
        let raw = self.read_standard(command::TEMPERATURE)?;
        Ok((raw as f32) / 10.0)
    }

    /// Read the battery voltage in volts.
    pub fn get_battery_voltage(&mut self) -> Result<f32, Error> {
        let raw = self.read_standard(command::VOLTAGE)?;
        Ok((raw as f32) / 1000.0)
    }

    /// Read the average battery current through the sense resistor in (amperes).
    pub fn get_battery_current(&mut self) -> Result<f32, Error> {
        let raw = self.read_standard(command::AVERAGE_CURRENT)? as i16;
        Ok((raw as f32) / 1000.0)
    }

    /// Get the battery state of charge (in percent, from 0 to 100).
    pub fn get_battery_level(&mut self) -> Result<f32, Error> {
        let raw = self.read_standard(command::STATE_OF_CHARGE)?;
        Ok(raw as f32)
    }

    /// Get the compensated capacity of the battery when fully charged in ampere hours.
    pub fn get_full_charge_capacity(&mut self) -> Result<f32, Error> {
        let raw = self.read_standard(command::FULL_CHARGE_CAPACITY)?;
        Ok((raw as f32) / 1000.0)
    }

    fn get_flags(&mut self) -> Result<u16, Error> {
        self.read_standard(command::FLAGS)
    }

    fn get_control_status(&mut self) -> Result<u16, Error> {
        self.read_control(command::CONTROL_STATUS)
    }

    fn read_standard(&mut self, command: command::Standard) -> Result<u16, Error> {
        let mut data = [0u8; 2];
        self.i2c
            .write_read(ADDRESS, &[command.0], &mut data)
            .map_err(|_| Error::I2cError)?;
        std::thread::sleep(WAIT_TIME);
        Ok(u16::from_le_bytes(data))
    }

    fn read_control(&mut self, command: command::Control) -> Result<u16, Error> {
        self.write_control(command)?;
        let mut data = [0u8; 2];
        self.i2c
            .write_read(ADDRESS, &[0x00], &mut data)
            .map_err(|_| Error::I2cError)?;
        std::thread::sleep(WAIT_TIME);
        Ok(u16::from_le_bytes(data))
    }

    fn write_control(&mut self, command: command::Control) -> Result<(), Error> {
        // At bus speeds above 100 kHz, only *single-byte* writes are allowed.
        let [a0, a1] = command.0.to_le_bytes();
        self.i2c
            .write(ADDRESS, &[0x00, a0])
            .map_err(|_| Error::I2cError)?;
        std::thread::sleep(WAIT_TIME);
        self.i2c
            .write(ADDRESS, &[0x01, a1])
            .map_err(|_| Error::I2cError)?;
        std::thread::sleep(WAIT_TIME);
        Ok(())
    }
}
