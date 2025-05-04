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
    /// DMG_ACCESS_MODE: 1 is ROM_READ, 3 is RAM_READ, 4 is RAM_WRITE
    dmg_access_mode: u8,
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
            dmg_access_mode: 0,
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
                    // TODO: send version information
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
                    log::debug!("SET_VARIABLE");
                    let mut payload = [0u8; 9];
                    self.stream.read_exact(&mut payload)?;

                    let size = payload[0];
                    let key = u32::from_be_bytes(payload[1..5].try_into().unwrap());
                    let value = u32::from_be_bytes(payload[5..9].try_into().unwrap());
                    self.set_variable(size, key, value);
                    self.write_ack()?;
                }
                0xA8 => {
                    log::info!("SET_ADDR_AS_INPUTS");
                    match Command::CartIdle.execute() {
                        Ok(()) => self.write_ack()?,
                        Err(_) => {
                            log::error!("Error setting cart idle");
                            self.write_one(0)?
                        }
                    }
                }
                0xA9 => {
                    let mut count = [0u8; 4];
                    self.stream.read_exact(&mut count)?;
                    let count = u32::from_be_bytes(count) as u16;
                    log::info!("CLK_TOGGLE: n={}", count);

                    for _ in 0..count {
                        let pins = SetPinsParams {
                            phi: Some(true),
                            ..Default::default()
                        };
                        Command::SetPins(pins).execute().unwrap();
                        esp_idf_svc::hal::delay::Ets::delay_us(1);
                        let pins = SetPinsParams {
                            phi: Some(false),
                            ..Default::default()
                        };
                        Command::SetPins(pins).execute().unwrap();
                    }
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
                0xB1 => {
                    log::debug!(
                        "DMG_CART_READ: addr={:X} count={:X}",
                        self.address,
                        self.transfer_size
                    );
                    let command = Command::DmgCartRead {
                        transfer: TransferParams {
                            cart_address: self.address,
                            transfer_count: self.transfer_size,
                            mem_address: 0,
                        },
                        cs_is_a15: self.dmg_access_mode <= 2,
                        cs_is_cs: self.dmg_access_mode > 2,
                    };
                    if command.execute().is_err() {
                        log::error!("DMG cart read failed");
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
                0xB2 => {
                    let mut address = [0u8; 4];
                    self.stream.read_exact(&mut address)?;
                    let address = u32::from_be_bytes(address) as u16;
                    let data = self.read_one()?;
                    log::debug!("DMG_CART_WRITE: {:04X} <= {:02X}", address, data);

                    if write_mem(0, &[data, 0, 0, 0]).is_err() {
                        log::error!("Mem write failed");
                        continue;
                    }
                    let command = Command::DmgCartWrite {
                        transfer: TransferParams {
                            cart_address: address as u32,
                            transfer_count: 1,
                            mem_address: 0,
                        },
                        cs_is_a15: true,
                        cs_is_cs: false,
                    };
                    if command.execute().is_err() {
                        log::error!("DMG cart write (one) failed");
                        continue;
                    }
                    self.write_ack()?;
                }
                0xB3 => {
                    log::info!("DMG_CART_WRITE_SRAM");
                    let mut buf = vec![0u8; self.transfer_size as usize];
                    self.stream.read_exact(&mut buf)?;
                    if write_mem(0, &buf).is_err() {
                        log::error!("Mem write failed");
                        continue;
                    }
                    let command = Command::DmgCartWrite {
                        transfer: TransferParams {
                            cart_address: self.address,
                            transfer_count: self.transfer_size,
                            mem_address: 0,
                        },
                        cs_is_a15: false,
                        cs_is_cs: true,
                    };
                    if command.execute().is_err() {
                        log::error!("DMG SRAM write failed");
                        continue;
                    }
                    self.address += self.transfer_size as u32;
                    self.write_ack()?;
                }
                0xB4 => {
                    log::info!("DMG_MBC_RESET");
                    // Set RST pin low
                    let pins = SetPinsParams {
                        pin30: Some(false),
                        pin30_dir: true,
                        ..Default::default()
                    };
                    if Command::SetPins(pins).execute().is_err() {
                        log::error!("DMG reset failed");
                        self.write_one(0)?;
                        continue;
                    }
                    std::thread::sleep(Duration::from_millis(1));
                    // Release it (it will float high)
                    if Command::CartIdle.execute().is_err() {
                        log::error!("Error setting cart idle");
                        self.write_one(0)?;
                        continue;
                    }
                    self.write_ack()?;
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
                0xC2 => {
                    log::debug!("AGB_CART_WRITE");
                    // Write a single word
                    let mut address = [0u8; 4];
                    self.stream.read_exact(&mut address)?;
                    let address = u32::from_be_bytes(address) as u16;
                    let mut data = [0u8; 2];
                    self.stream.read_exact(&mut data)?;

                    if write_mem(0, &[data[1], data[0], 0, 0]).is_err() {
                        log::error!("Mem write failed");
                        self.write_one(0)?;
                        continue;
                    }
                    let command = Command::AgbRomWrite(TransferParams {
                        cart_address: address as u32,
                        transfer_count: 1,
                        mem_address: 0,
                    });
                    if command.execute().is_err() {
                        log::error!("AGB CART WRITE failed");
                        self.write_one(0)?;
                        continue 'main;
                    }
                    self.write_ack()?;
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
                0xC7 => {
                    log::info!("AGB_CART_WRITE_FLASH_DATA");
                    let flash_type = self.read_one()?;
                    let mut buf = vec![0u8; self.transfer_size as usize];
                    self.stream.read_exact(&mut buf)?;

                    match flash_type {
                        1 => {
                            let command_location = 32 * 1024;
                            let commands = [0xAA, 0x55, 0xA0, 0];
                            write_mem(command_location, &commands).unwrap();
                            write_mem(0, &buf).unwrap();

                            // Regular flash
                            for i in 0..buf.len() {
                                let writes = [
                                    (0x5555, command_location + 0),
                                    (0x2AAA, command_location + 1),
                                    (0x5555, command_location + 2),
                                    (self.address as u16, i as u16),
                                ];
                                for (cart_address, mem_address) in writes {
                                    Command::AgbRamWrite(TransferParams {
                                        cart_address: cart_address as u32,
                                        transfer_count: 1,
                                        mem_address,
                                    })
                                    .execute()
                                    .unwrap();
                                }

                                // Wait for 20 microseconds between writes
                                esp_idf_svc::hal::delay::Ets::delay_us(20);

                                self.address += 1;
                            }
                        }
                        2 => {
                            // Atmel flash
                            log::error!("Atmel write not implemented");
                        }
                        _ => {
                            log::error!("Unknown flash type {}", flash_type);
                            self.write_one(0)?;
                            continue 'main;
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

                    const GPIO_IO: u32 = 0xC4;
                    const GPIO_DIR: u32 = 0xC6;
                    const GPIO_CTRL: u32 = 0xC8;

                    fn gpio_write_reg(reg: u32, value: u8) {
                        let buf = [value, 0, 0, 0];
                        write_mem(0, &buf).unwrap();
                        let command: Command = Command::AgbRomWrite(TransferParams {
                            cart_address: reg / 2,
                            transfer_count: 1,
                            mem_address: 0,
                        });
                        command.execute().unwrap();
                    }

                    fn gpio_read_reg(reg: u32) -> u8 {
                        let command = Command::AgbRomRead(TransferParams {
                            cart_address: reg / 2,
                            transfer_count: 1,
                            mem_address: 0,
                        });
                        command.execute().unwrap();
                        let mut buf = [0u8; 4];
                        read_mem(0, &mut buf).unwrap();
                        buf[0]
                    }

                    // RTC: S3511   [[__] CS SIO SCK]
                    // CS: active high
                    // SCK: idles high, read data on rising edge  [min width: 1 us]
                    // SIO: commands MSB first, data LSB first

                    fn rtc_write_read(wdata: &[u8], rdata: &mut [u8]) {
                        gpio_write_reg(GPIO_DIR, 0b111); // All outputs

                        // Write data (command)
                        for data in wdata {
                            let mut data = *data;
                            for _ in 0..8 {
                                gpio_write_reg(GPIO_IO, 0b100 | ((data & 1) << 1));
                                gpio_write_reg(GPIO_IO, 0b101 | ((data & 1) << 1));
                                data >>= 1;
                            }
                        }

                        // Read data
                        gpio_write_reg(GPIO_DIR, 0b101);
                        for data in rdata {
                            *data = 0;
                            for i in 0..8 {
                                gpio_write_reg(GPIO_IO, 0b100);
                                let bit = (gpio_read_reg(GPIO_IO) >> 1) & 1;
                                *data |= (bit as u8) << i;
                                gpio_write_reg(GPIO_IO, 0b101);
                            }
                        }

                        // Release CS
                        gpio_write_reg(GPIO_IO, 0b001);
                    }

                    gpio_write_reg(GPIO_CTRL, 1); // Set GPIO registers R/W
                    gpio_write_reg(GPIO_IO, 0b001); // Initial state: SCK high
                    let mut buffer = [0u8; 8];

                    // Command: 0x63 (read status)
                    let command = (0x63u8).reverse_bits();
                    rtc_write_read(&[command], &mut buffer[0..1]);

                    // Command: 0x65 (read date and time)
                    let command = (0x65u8).reverse_bits();
                    rtc_write_read(&[command], &mut buffer[1..8]);

                    gpio_write_reg(GPIO_DIR, 0b000);
                    gpio_write_reg(GPIO_CTRL, 0);
                    self.stream.write_all(&buffer)?;
                }
                0xD4 => {
                    log::debug!("CART_WRITE_FLASH_CMD");
                    set_waits(2, 1, 8).unwrap();

                    let _is_flashcart = self.read_one()? == 1;
                    let count = self.read_one()?;

                    for _ in 0..count {
                        let mut address = [0u8; 4];
                        self.stream.read_exact(&mut address)?;
                        let address = u32::from_be_bytes(address) as u16;
                        let mut data = [0u8; 2];
                        self.stream.read_exact(&mut data)?;
                        let data = u16::from_be_bytes(data) as u8;

                        if write_agb_sram_one(address, data).is_err() {
                            log::error!("AGB Flash Command failed");
                            continue 'main;
                        }
                    }
                    self.write_ack()?;
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
            (SIZE_8, 0x01) => self.dmg_access_mode = value as u8,
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
            (SIZE_8, 0x01) => self.dmg_access_mode as u32,
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

#[derive(Copy, Clone, Default, Debug)]
struct SetPinsParams {
    phi: Option<bool>,
    wr: Option<bool>,
    rd: Option<bool>,
    cs: Option<bool>,
    pin30: Option<bool>,
    pin31: Option<bool>,
    pin30_dir: bool,
    pin31_dir: bool,
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
    DmgCartRead {
        transfer: TransferParams,
        cs_is_a15: bool,
        cs_is_cs: bool,
    },
    DmgCartWrite {
        transfer: TransferParams,
        cs_is_a15: bool,
        cs_is_cs: bool,
    },
    CartIdle,
    SetPins(SetPinsParams),
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
            DmgCartRead {
                transfer,
                cs_is_a15,
                cs_is_cs,
            } => {
                (6 << 56)
                    | transfer.encode()
                    | ((cs_is_a15 as u64) << 16)
                    | ((cs_is_cs as u64) << 17)
            }
            DmgCartWrite {
                transfer,
                cs_is_a15,
                cs_is_cs,
            } => {
                (7 << 56)
                    | transfer.encode()
                    | ((cs_is_a15 as u64) << 16)
                    | ((cs_is_cs as u64) << 17)
            }
            CartIdle => 8 << 56,
            SetPins(p) => {
                (9 << 56)
                    | ((p.pin30_dir as u64) << 13)
                    | ((p.pin31_dir as u64) << 12)
                    | ((p.phi.is_some() as u64) << 11)
                    | ((p.wr.is_some() as u64) << 10)
                    | ((p.rd.is_some() as u64) << 9)
                    | ((p.cs.is_some() as u64) << 8)
                    | ((p.pin30.is_some() as u64) << 7)
                    | ((p.pin31.is_some() as u64) << 6)
                    | ((p.phi.unwrap_or_default() as u64) << 5)
                    | ((p.wr.unwrap_or_default() as u64) << 4)
                    | ((p.rd.unwrap_or_default() as u64) << 3)
                    | ((p.cs.unwrap_or_default() as u64) << 2)
                    | ((p.pin30.unwrap_or_default() as u64) << 1)
                    | ((p.pin31.unwrap_or_default() as u64) << 0)
            }
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

fn write_agb_sram_one(address: u16, data: u8) -> Result<(), fpga::Error> {
    log::debug!("... [{:04X}] <- {:02X}", address, data);
    let buf = [data, 0, 0, 0];
    write_mem(0, &buf)?;

    let command = Command::AgbRamWrite(TransferParams {
        cart_address: address as u32,
        transfer_count: 1,
        mem_address: 0,
    });
    command.execute()
}
