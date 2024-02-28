use thiserror::Error;

use embedded_hal::i2c::I2c;

const ADDRESS: u8 = 0x51;

#[derive(Debug, Error)]
pub enum Error {
    #[error("i2c error")]
    I2cError,
}

pub struct PCF8563<I2C: I2c> {
    i2c: I2C,
}

impl<I2C> PCF8563<I2C>
where
    I2C: I2c,
{
    pub fn new(i2c: I2C) -> Self {
        PCF8563 { i2c }
    }

    /// Returns the current Datetime, or None if the time is unreliable.
    pub fn read_datetime(&mut self) -> Result<Option<Datetime>, Error> {
        let mut data = [0u8; 7];
        self.i2c
            .write_read(ADDRESS, &[0x02], &mut data)
            .map_err(|_| Error::I2cError)?;

        // TODO check the VL seconds flag and return None
        let unreliable = (data[0] & 0x80) != 0;
        if unreliable {
            Ok(None)
        } else {
            Ok(Some(Datetime {
                seconds: decode_bcd(data[0] & 0x7F),
                minutes: decode_bcd(data[1] & 0x7F),
                hours: decode_bcd(data[2] & 0x3F),
                days: decode_bcd(data[3] & 0x3F),
                weekdays: decode_bcd(data[4] & 0x07),
                months: decode_bcd(data[5] & 0x1F),
                years: (decode_bcd(data[6]) as u16) + (100 * (data[5] >> 7) as u16),
            }))
        }
    }

    /// Write the Datetime to the RTC.
    pub fn write_datetime(&mut self, datetime: Datetime) -> Result<(), Error> {
        let data = [
            0x2, // Register to write
            encode_bcd(datetime.seconds),
            encode_bcd(datetime.minutes),
            encode_bcd(datetime.hours),
            encode_bcd(datetime.days),
            encode_bcd(datetime.weekdays),
            encode_bcd(datetime.months) | (if datetime.years >= 100 { 0x80 } else { 0x00 }),
            encode_bcd((datetime.years % 100) as u8),
        ];
        self.i2c.write(ADDRESS, &data).map_err(|_| Error::I2cError)
    }
}

fn decode_bcd(bcd: u8) -> u8 {
    let ones = bcd & 0xF;
    let tens = (bcd & 0xF0) >> 4;
    (10 * tens) + ones
}

fn encode_bcd(data: u8) -> u8 {
    let ones = data % 10;
    let tens: u8 = data / 10;
    (tens << 4) | ones
}

#[derive(Copy, Clone, Debug, Default)]
pub struct Datetime {
    pub seconds: u8,
    pub minutes: u8,
    pub hours: u8,
    pub days: u8,
    pub weekdays: u8,
    pub months: u8,
    pub years: u16,
}

impl Datetime {
    pub fn to_timestamp(self) -> u64 {
        todo!()
    }

    pub fn from_timestamp(timestamp: u64) -> Self {
        todo!()
    }
}
