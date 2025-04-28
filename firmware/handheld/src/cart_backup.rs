use std::{
    io::{Read, Write},
    time::{Duration, Instant},
};

use crate::device::{
    drivers::{
        fpga::{self, FpgaSpiWordSize, SpiCommand},
        usb::CdcStream,
    },
    Device,
};
use esp_idf_svc::hal::units::FromValueType;

const REG_BASE: u32 = 0xC020_0000;
const REG_STATUS_IDLE: u32 = REG_BASE | 0x0;
const REG_WAITS: u32 = REG_BASE | 0x4;
const REG_INSTRUCTION_LO: u32 = REG_BASE | 0x100;
const REG_INSTRUCTION_HI: u32 = REG_BASE | 0x104;
const REG_INSTRUCTION_GO: u32 = REG_BASE | 0x108;
const MEM_BASE: u32 = 0xC030_0000;

pub fn start_task(cdc_interface: u32) {
    std::thread::Builder::new()
        .name("cart_backup".to_string())
        .stack_size(16 * 1024)
        .spawn(move || {
            let stream = CdcStream::new(cdc_interface);
            let mut cart = CartBackup::new(stream);
            let _ = cart.go();
        })
        .unwrap();
}

struct CartBackup {
    stream: CdcStream,

    cart_voltage: CartVoltage,
    cart_mode: CartMode,
    cart_powered: bool,

    /// ADDRESS: firmware variable (unit: words)
    address: u32,
    /// TRANSFER_SIZE: firmware variable (unit: bytes?)
    transfer_size: u16,
    /// CART_MODE: 1 is DMG, 2 is AGB?
    cart_mode_variable: u8,
}

impl CartBackup {
    fn new(stream: CdcStream) -> Self {
        CartBackup {
            stream,
            cart_voltage: CartVoltage::Voltage3v3,
            cart_mode: CartMode::Agb,
            cart_powered: false,
            address: 0,
            transfer_size: 0,
            cart_mode_variable: 0,
        }
    }

    fn read_one(&mut self) -> std::io::Result<u8> {
        let mut data = 0u8;
        self.stream.read(std::slice::from_mut(&mut data))?;
        Ok(data)
    }

    fn write_one(&mut self, data: u8) -> std::io::Result<()> {
        self.stream.write_all(&[data])
    }

    fn write_ack(&mut self) -> std::io::Result<()> {
        self.write_one(1)
    }

    fn go(&mut self) -> std::io::Result<()> {
        'main: loop {
            let data = self.read_one()?;
            match data {
                0xA1 => {
                    // QUERY_FW_INFO
                    // TODO: send verison information
                    log::info!("QUERY_FW_INFO");
                    self.stream.write(&[0xE1])?;
                }
                0xA2 => {
                    log::info!("SET_MODE_AGB");
                    self.cart_mode = CartMode::Agb;
                    set_waits(2, 1, 8).unwrap();
                    self.write_ack()?;
                }
                0xA3 => {
                    log::info!("SET_MODE_DMG");
                    self.cart_mode = CartMode::Dmg;
                    self.write_ack()?;
                }
                0xA4 => {
                    log::info!("SET_VOLTAGE_3_3V");
                    self.cart_voltage = CartVoltage::Voltage3v3;
                    // TODO: actually set voltage
                    self.write_ack()?;
                }
                0xA5 => {
                    log::info!("SET_VOLTAGE_5V");
                    self.cart_voltage = CartVoltage::Voltage5v0;
                    // TODO: actually set voltage
                    self.write_ack()?;
                }
                0xA6 => {
                    // SET_VARIABLE
                    log::info!("SET_VARIABLE");
                    let mut payload = [0u8; 9];
                    self.stream.read_exact(&mut payload)?;

                    let size = payload[0];
                    let key = u32::from_be_bytes(payload[1..5].try_into().unwrap());
                    let value = u32::from_be_bytes(payload[5..9].try_into().unwrap());
                    self.set_variable(size, key, value);
                    self.write_ack()?;
                }
                0xAB => {
                    log::info!("ENABLE_PULLUPS");
                    self.write_ack()?;
                }
                0xAC => {
                    log::info!("DISABLE_PULLUPS");
                    self.write_ack()?;
                }
                0xAD => {
                    log::info!("GET_VARIABLE");
                    let mut payload = [0u8; 5];
                    self.stream.read_exact(&mut payload)?;
                    let size = payload[0];
                    let key = u32::from_be_bytes(payload[1..5].try_into().unwrap());
                    let value = self.get_variable(size, key);
                    self.stream.write_all(&value.to_be_bytes())?;
                }
                0xC1 => {
                    log::debug!(
                        "AGB_CART_READ: addr={:X} count={:X}",
                        self.address,
                        self.transfer_size
                    );
                    let command = Command::AgbRomRead(TransferParams {
                        cart_address: self.address,
                        transfer_count: self.transfer_size / 2,
                        mem_address: 0,
                    });
                    if command.execute().is_err() {
                        log::error!("AGB ROM read failed");
                        continue;
                    }
                    let mut buf = vec![0u8; self.transfer_size as usize];
                    if read_mem(0, &mut buf).is_err() {
                        log::error!("Mem read failed");
                        continue;
                    }
                    self.address += (self.transfer_size / 2) as u32;
                    self.stream.write_all(&buf)?;
                }
                0xC3 => {
                    log::debug!("AGB_CART_READ_SRAM");
                    let command = Command::AgbRamRead(TransferParams {
                        cart_address: self.address,
                        transfer_count: self.transfer_size,
                        mem_address: 0,
                    });
                    if command.execute().is_err() {
                        log::error!("AGB SRAM read failed");
                        continue;
                    }
                    let mut buf = vec![0u8; self.transfer_size as usize];
                    if read_mem(0, &mut buf).is_err() {
                        log::error!("Mem read failed");
                        continue;
                    }
                    self.address += self.transfer_size as u32;
                    self.stream.write_all(&buf)?;
                }
                0xC4 => {
                    log::debug!("AGB_CART_WRITE_SRAM");
                    let mut buf = vec![0u8; self.transfer_size as usize];
                    self.stream.read_exact(&mut buf)?;
                    if write_mem(0, &buf).is_err() {
                        log::error!("Mem write failed");
                        continue;
                    }
                    let command = Command::AgbRamWrite(TransferParams {
                        cart_address: self.address,
                        transfer_count: self.transfer_size,
                        mem_address: 0,
                    });
                    if command.execute().is_err() {
                        log::error!("AGB SRAM write failed");
                        continue;
                    }
                    self.address += self.transfer_size as u32;
                    self.write_ack()?;
                }
                0xC5 => {
                    log::debug!("AGB_CART_READ_EEPROM");
                    let eeprom_type = self.read_one()?;
                    let address_bits = match eeprom_type {
                        1 => 6,
                        2 => 14,
                        _ => {
                            log::error!("Unknown eeprom type {}", eeprom_type);
                            continue;
                        }
                    };
                    let transfer_count = (self.transfer_size / 8) as usize;
                    let mut buf = Vec::<u8>::new();
                    for _ in 0..transfer_count {
                        const EEPROM_ADDRESS: u32 = 0x1FFFF00 >> 1;
                        // Fill buffer with 16-bit little endian words (only bit 0 matters)
                        buf.clear();

                        // "11": read request
                        buf.extend_from_slice(&[1, 0, 1, 0]);
                        for i in 0..address_bits {
                            // Fill address, MSB first
                            buf.push((self.address >> (address_bits - 1 - i)) as u8 & 1);
                            buf.push(0);
                        }
                        buf.extend_from_slice(&[0, 0, 0, 0]); // Pad to 4 bytes
                        write_mem(0, &buf).unwrap();
                        let command = Command::AgbRomWrite(TransferParams {
                            cart_address: EEPROM_ADDRESS,
                            transfer_count: (2 + address_bits + 1) as u16,
                            mem_address: 0,
                        });
                        if command.execute().is_err() {
                            log::error!("AGB EEPROM command failed");
                            continue 'main;
                        }
                        self.address += 1;

                        buf.clear();
                        buf.resize(68 * 2, 0);
                        let command = Command::AgbRomRead(TransferParams {
                            cart_address: EEPROM_ADDRESS,
                            transfer_count: (buf.len() / 2) as u16,
                            mem_address: 0,
                        });
                        if command.execute().is_err() {
                            log::error!("AGB EEPROM read failed");
                            continue 'main;
                        }
                        read_mem(0, &mut buf).unwrap();

                        // Extract the 64 bit data (first 4 bits are skipped, overwritten).
                        let mut word = 0u64;
                        for x in buf.iter().step_by(2) {
                            word <<= 1;
                            word |= (x & 1) as u64;
                        }
                        self.stream.write_all(&word.to_be_bytes())?;
                    }
                }
                0xC6 => {
                    log::debug!("AGB_CART_WRITE_EEPROM");
                    set_waits(8, 8, 0).unwrap();
                    let eeprom_type = self.read_one()?;
                    let address_bits = match eeprom_type {
                        1 => 6,
                        2 => 14,
                        _ => {
                            log::error!("Unknown eeprom type {}", eeprom_type);
                            continue;
                        }
                    };
                    let transfer_count = (self.transfer_size / 8) as usize;
                    let mut buf = Vec::<u8>::new();
                    for _ in 0..transfer_count {
                        const EEPROM_ADDRESS: u32 = 0x1FFFF00 >> 1;

                        // Read the data word
                        let mut data = [0u8; 8];
                        self.stream.read_exact(&mut data)?;
                        let data = u64::from_be_bytes(data);

                        // Fill buffer with 16-bit little endian words (only bit 0 matters)
                        buf.clear();
                        // "10": write request
                        buf.extend_from_slice(&[1, 0, 0, 0]);
                        for i in 0..address_bits {
                            // Fill address, MSB first
                            buf.push((self.address >> (address_bits - 1 - i)) as u8 & 1);
                            buf.push(0);
                        }
                        for i in 0..64 {
                            buf.push((data >> (63 - i)) as u8 & 1);
                            buf.push(0);
                        }
                        buf.extend_from_slice(&[0, 0, 0, 0]); // end with "0", pad to 4 bytes
                        write_mem(0, &buf).unwrap();
                        let command = Command::AgbRomWrite(TransferParams {
                            cart_address: EEPROM_ADDRESS,
                            transfer_count: (2 + address_bits + 64 + 1) as u16,
                            mem_address: 0,
                        });
                        if command.execute().is_err() {
                            log::error!("AGB EEPROM command failed");
                            continue;
                        }
                        self.address += 1;

                        // Read from EEPROM until it returns 1 to ack the write
                        buf.resize(4, 0);
                        let deadline = Instant::now() + Duration::from_millis(100);
                        loop {
                            if Instant::now() > deadline {
                                log::error!("EEPROM failed to respond to write");
                                continue 'main;
                            }
                            let command = Command::AgbRomRead(TransferParams {
                                cart_address: EEPROM_ADDRESS,
                                transfer_count: 2,
                                mem_address: 0,
                            });
                            if command.execute().is_err() {
                                log::error!("AGB EEPROM read failed");
                                continue 'main;
                            }
                            read_mem(0, &mut buf).unwrap();
                            std::thread::sleep(Duration::from_millis(3));
                            if (buf[0] & 1) == 1 {
                                break;
                            }
                        }
                    }
                    self.write_ack()?;
                }
                0xC9 => {
                    log::info!("AGB_BOOTUP_SEQUENCE");
                    // TODO: ...?
                    self.write_ack()?;
                }
                0xCA => {
                    log::info!("AGB_READ_GPIO_RTC");
                    // STUB: todo implement
                    self.stream
                        .write_all(&[0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF])?;
                }
                0xF2 => {
                    log::info!("CART_PWR_ON");
                    match Command::CartPower(true).execute() {
                        Ok(_) => {
                            self.cart_powered = true;
                            self.write_ack()?;
                        }
                        Err(_) => {
                            log::error!("Cart power on failed");
                            self.write_one(0)?;
                        }
                    }
                }
                0xF3 => {
                    log::info!("CART_PWR_OFF");
                    match Command::CartPower(false).execute() {
                        Ok(_) => {
                            self.cart_powered = false;
                            self.write_ack()?;
                        }
                        Err(_) => {
                            log::error!("Cart power on failed");
                            self.write_one(0)?;
                        }
                    }
                }
                0xF4 => {
                    log::info!("QUERY_CART_PWR");
                    self.stream.write_all(&[self.cart_powered as u8])?;
                }
                _ => {
                    log::info!("Unknown command 0x{:X}", data);
                }
            }
            self.stream.flush()?;
        }
    }

    fn set_variable(&mut self, size: u8, key: u32, value: u32) {
        const SIZE_8: u8 = 1;
        const SIZE_16: u8 = 2;
        const SIZE_32: u8 = 4;

        match (size, key) {
            // ADDRESS
            (SIZE_32, 0x00) => self.address = value,
            // AUTO_POWEROFF_TIME
            (SIZE_32, 0x01) => {}
            // TRANSFER_SIZE
            (SIZE_16, 0x00) => self.transfer_size = value as u16,
            // BUFFER_SIZE
            (SIZE_16, 0x01) => {}
            // DMG_ROM_BANK
            (SIZE_16, 0x02) => {}
            // STATUS_REGISTER
            (SIZE_16, 0x03) => {}
            // LAST_BANK_ACCESSED
            (SIZE_16, 0x04) => {}
            // STATUS_REGISTER_MASK
            (SIZE_16, 0x05) => {}
            // STATUS_REGISTER_VALUE
            (SIZE_16, 0x06) => {}
            // CART_MODE
            (SIZE_8, 0x00) => self.cart_mode_variable = value as u8,
            // DMG_ACCESS_MODE
            (SIZE_8, 0x01) => {}
            // FLASH_COMMAND_SET
            (SIZE_8, 0x02) => {}
            // FLASH_METHOD
            (SIZE_8, 0x03) => {}
            // FLASH_WE_PIN
            (SIZE_8, 0x04) => {}
            // FLASH_PULSE_RESET
            (SIZE_8, 0x05) => {}
            // FLASH_COMMANDS_BANK_1
            (SIZE_8, 0x06) => {}
            // FLASH_SHARP_VERIFY_SR
            (SIZE_8, 0x07) => {}
            // DMG_READ_CS_PULSE
            (SIZE_8, 0x08) => {}
            // DMG_WRITE_CS_PULSE
            (SIZE_8, 0x09) => {}
            // FLASH_DOUBLE_DIE
            (SIZE_8, 0x0A) => {}
            // DMG_READ_METHOD
            (SIZE_8, 0x0B) => {}
            // AGB_READ_METHOD
            (SIZE_8, 0x0C) => {}
            // CART_POWERED
            (SIZE_8, 0x0D) => {}
            // PULLUPS_ENABLED
            (SIZE_8, 0x0E) => {}
            // AUTO_POWEROFF_ENABLED
            (SIZE_8, 0x0F) => {}
            // AGB_IRQ_ENABLED
            (SIZE_8, 0x10) => {}
            _ => {}
        }
    }

    fn get_variable(&self, size: u8, key: u32) -> u32 {
        const SIZE_8: u8 = 1;
        const SIZE_16: u8 = 2;
        const SIZE_32: u8 = 4;

        match (size, key) {
            // ADDRESS
            (SIZE_32, 0x00) => self.address,
            // AUTO_POWEROFF_TIME
            (SIZE_32, 0x01) => 0,
            // TRANSFER_SIZE
            (SIZE_16, 0x00) => self.transfer_size as u32,
            // BUFFER_SIZE
            (SIZE_16, 0x01) => 0,
            // DMG_ROM_BANK
            (SIZE_16, 0x02) => 0,
            // STATUS_REGISTER
            (SIZE_16, 0x03) => 0,
            // LAST_BANK_ACCESSED
            (SIZE_16, 0x04) => 0,
            // STATUS_REGISTER_MASK
            (SIZE_16, 0x05) => 0,
            // STATUS_REGISTER_VALUE
            (SIZE_16, 0x06) => 0,
            // CART_MODE
            (SIZE_8, 0x00) => self.cart_mode_variable as u32,
            // DMG_ACCESS_MODE
            (SIZE_8, 0x01) => 0,
            // FLASH_COMMAND_SET
            (SIZE_8, 0x02) => 0,
            // FLASH_METHOD
            (SIZE_8, 0x03) => 0,
            // FLASH_WE_PIN
            (SIZE_8, 0x04) => 0,
            // FLASH_PULSE_RESET
            (SIZE_8, 0x05) => 0,
            // FLASH_COMMANDS_BANK_1
            (SIZE_8, 0x06) => 0,
            // FLASH_SHARP_VERIFY_SR
            (SIZE_8, 0x07) => 0,
            // DMG_READ_CS_PULSE
            (SIZE_8, 0x08) => 0,
            // DMG_WRITE_CS_PULSE
            (SIZE_8, 0x09) => 0,
            // FLASH_DOUBLE_DIE
            (SIZE_8, 0x0A) => 0,
            // DMG_READ_METHOD
            (SIZE_8, 0x0B) => 0,
            // AGB_READ_METHOD
            (SIZE_8, 0x0C) => 0,
            // CART_POWERED
            (SIZE_8, 0x0D) => 0,
            // PULLUPS_ENABLED
            (SIZE_8, 0x0E) => 0,
            // AUTO_POWEROFF_ENABLED
            (SIZE_8, 0x0F) => 0,
            // AGB_IRQ_ENABLED
            (SIZE_8, 0x10) => 0,
            _ => 0,
        }
    }
}

#[derive(Copy, Clone, Debug)]
enum CartVoltage {
    Voltage3v3,
    Voltage5v0,
}

#[derive(Copy, Clone, Debug)]
enum CartMode {
    Dmg,
    Agb,
}

#[derive(Copy, Clone, Debug)]
struct TransferParams {
    /// Start cartridge address of the transfer (in words)
    cart_address: u32,
    /// Transfer count (words)
    transfer_count: u16,
    /// Start memory buffer address of the transfer (bytes)
    mem_address: u16,
}

impl TransferParams {
    fn encode(self) -> u64 {
        ((self.cart_address as u64) & 0xFFFFFF)
            | ((self.transfer_count as u64) << 24)
            | ((self.mem_address as u64) << 40)
    }
}

#[allow(unused)]
#[derive(Copy, Clone, Debug)]
enum Command {
    Nop,
    CartPower(bool),
    AgbRomRead(TransferParams),
    AgbRamRead(TransferParams),
    AgbRomWrite(TransferParams),
    AgbRamWrite(TransferParams),
}

impl Command {
    fn encode(self) -> u64 {
        use Command::*;
        match self {
            Nop => 0,
            CartPower(enable) => (1 << 56) | (enable as u64),
            AgbRomRead(t) => (2 << 56) | t.encode(),
            AgbRamRead(t) => (3 << 56) | t.encode(),
            AgbRomWrite(t) => (4 << 56) | t.encode(),
            AgbRamWrite(t) => (5 << 56) | t.encode(),
        }
    }

    fn execute(self) -> Result<(), fpga::Error> {
        let encoded = self.encode();
        {
            // TODO: coalesce into one write
            let mut device = Device::lock();
            device
                .fpga
                .write_u32(REG_INSTRUCTION_LO, (encoded & 0xFFFFFFFF) as u32)?;
            device
                .fpga
                .write_u32(REG_INSTRUCTION_HI, ((encoded >> 32) & 0xFFFFFFFF) as u32)?;
            device.fpga.write_u32(REG_INSTRUCTION_GO, 1)?;
        }

        // TODO: wait timeout
        loop {
            let mut device = Device::lock();
            if device.fpga.read_u32(REG_STATUS_IDLE)? == 1 {
                break;
            }
            std::mem::drop(device);
            std::thread::sleep(Duration::from_millis(1));
        }

        Ok(())
    }
}

fn read_mem(address: u16, buffer: &mut [u8]) -> Result<(), fpga::Error> {
    let command = SpiCommand {
        word_size: FpgaSpiWordSize::Bits32,
        byte_swap: true,
        increment_address: true,
    };
    let address = MEM_BASE | (address as u32);
    Device::lock()
        .fpga
        .spi_read(Some(10.MHz().into()), command, address, buffer)
}

fn write_mem(address: u16, buffer: &[u8]) -> Result<(), fpga::Error> {
    assert!(buffer.len() % 4 == 0);
    let command = SpiCommand {
        word_size: FpgaSpiWordSize::Bits32,
        byte_swap: true,
        increment_address: true,
    };
    let address = MEM_BASE | (address as u32);
    Device::lock()
        .fpga
        .spi_write(Some(40.MHz().into()), command, address, buffer)
}

#[allow(unused)]
fn set_waits(wait0: u32, wait1: u32, wait2: u32) -> Result<(), fpga::Error> {
    Device::lock().fpga.write_u32(
        REG_WAITS,
        (wait0 & 0xF) | ((wait1 & 0xF) << 4) | ((wait2 & 0xF) << 8),
    )
}
