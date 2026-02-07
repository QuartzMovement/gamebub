use core::fmt::{Display, Formatter};

use esp_hal::efuse::{self, Efuse};

#[derive(Copy, Clone)]
pub struct SerialNumber(pub u32);

impl SerialNumber {
    pub fn get() -> SerialNumber {
        SerialNumber(get_user_data(2))
    }
}

impl Display for SerialNumber {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        write!(f, "{:08X}", self.0)
    }
}

fn get_user_data(index: usize) -> u32 {
    Efuse::read_field_le::<[u32; 6]>(efuse::BLOCK_USR_DATA)[index]
}
