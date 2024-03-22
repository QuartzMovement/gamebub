#![allow(dead_code)]

use embedded_hal::i2c::I2c;
use thiserror::Error;

const ADDRESS: u8 = 0x36;

#[derive(Debug, Error)]
pub enum Error {
    #[error("i2c error")]
    I2cError,
}

pub struct MAX17048<I2C: I2c> {
    i2c: I2C,
}

impl<I2C> MAX17048<I2C>
where
    I2C: I2c,
{
    pub fn new(i2c: I2C) -> Self {
        MAX17048 { i2c }
    }

    /// Get the battery state of charge, in percent from 0 to 100.
    pub fn get_battery_level(&mut self) -> Result<f32, Error> {
        let raw = self.read_reg(0x4)?;
        Ok(((raw as f32) / 256.0).clamp(0.0, 100.0))
    }

    /// Get the voltage of the battery, in volts.
    pub fn get_battery_voltage(&mut self) -> Result<f32, Error> {
        let raw = self.read_reg(0x2)?;
        Ok(((raw as f32) * 78.125) / 1_000_000.0)
    }

    /// Get the charge or discharge rate of the battery, in %/hour.
    pub fn get_battery_charge_rate(&mut self) -> Result<f32, Error> {
        let raw = self.read_reg(0x16)?;
        Ok((raw as f32) * 0.208)
    }

    fn read_reg(&mut self, reg: u8) -> Result<u16, Error> {
        let mut data = [0u8; 2];
        self.i2c
            .write_read(ADDRESS, &[reg], &mut data)
            .map_err(|_| Error::I2cError)?;
        Ok(u16::from_be_bytes(data))
    }
}
