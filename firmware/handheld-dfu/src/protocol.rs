use core::cell::RefCell;

use embassy_sync::blocking_mutex::CriticalSectionMutex;
use embedded_storage::nor_flash::{NorFlash, ReadNorFlash};
use esp_storage::FlashStorage;

static STATE: CriticalSectionMutex<RefCell<CommandState>> =
    CriticalSectionMutex::new(RefCell::new(CommandState::new()));

#[repr(C)]
#[derive(Copy, Clone)]
pub struct CommandHeader {
    pub magic: u32,
    pub token: u32,
    pub cmd_id: u8,
    pub cmd_size: u8,
    pub _reserved: u16,
    pub transfer_length: u32,
    pub args: [u8; 16],
}

const FLASH_BASE_ADDR: u32 = 0x3C00_0000;
/// Flash below this offset is write-protected.
const FLASH_PROTECT_OFFSET: u32 = 0x8_0000;

impl From<[u8; 32]> for CommandHeader {
    fn from(value: [u8; 32]) -> Self {
        CommandHeader {
            magic: u32::from_le_bytes(value[0..4].try_into().unwrap()),
            token: u32::from_le_bytes(value[4..8].try_into().unwrap()),
            cmd_id: value[8],
            cmd_size: value[9],
            _reserved: u16::from_le_bytes(value[10..12].try_into().unwrap()),
            transfer_length: u32::from_le_bytes(value[12..16].try_into().unwrap()),
            args: value[16..32].try_into().unwrap(),
        }
    }
}

impl CommandHeader {
    pub const MAGIC: u32 = 0x431FD10B;

    pub fn is_in(&self) -> bool {
        (self.cmd_id & 0x80) != 0
    }
}

#[derive(Copy, Clone, Debug)]
#[repr(u32)]
#[allow(unused)]
pub enum CommandStatus {
    Ok = 0,
    UnknownCmd = 1,
    InvalidCmdLength = 2,
    InvalidTransferLength = 3,
    InvalidAddress = 4,
    BadAlignment = 5,
    InterleavedWrite = 6,
    Rebooting = 7,
    UnknownError = 8,
    InvalidState = 9,
    NotPermitted = 10,
    InvalidArg = 11,
    BufferTooSmall = 12,
    PreconditionNotMet = 13,
    ModifiedData = 14,
    InvalidData = 15,
    NotFound = 16,
    UnsupportedModification = 17,
}

/// Handler for the protocol
pub struct Protocol {
    flash: FlashStorage<'static>,
}

impl Protocol {
    pub fn new(flash: FlashStorage<'static>) -> Self {
        Protocol { flash }
    }

    pub async fn handle(
        &mut self,
        command: CommandHeader,
        read_fn: impl AsyncFnMut(&mut [u8]) -> Result<usize, CommandStatus>,
        write_fn: impl AsyncFnMut(&[u8]) -> Result<(), CommandStatus>,
    ) -> Result<(), CommandStatus> {
        STATE.lock(|x| {
            *x.borrow_mut() = CommandState {
                token: command.token,
                status: CommandStatus::Ok,
                command_id: command.cmd_id,
                in_progress: true,
            };
        });
        let result = match command.cmd_id {
            // FLASH_ERASE
            0x03 => self.command_flash_erase(command).await,
            // READ
            0x84 => self.command_read(command, write_fn).await,
            // WRITE
            0x05 => self.command_write(command, read_fn).await,
            // TODO efuse
            _ => Err(CommandStatus::UnknownCmd),
        };
        STATE.lock(|x| {
            *x.borrow_mut() = CommandState {
                token: command.token,
                status: match result {
                    Ok(()) => CommandStatus::Ok,
                    Err(e) => e,
                },
                command_id: command.cmd_id,
                in_progress: false,
            };
        });
        result
    }

    async fn command_flash_erase(&mut self, command: CommandHeader) -> Result<(), CommandStatus> {
        // Validate arguments.
        if command.cmd_size != 8 {
            return Err(CommandStatus::InvalidCmdLength);
        }
        let address = u32::from_le_bytes(command.args[0..4].try_into().unwrap());
        let length = u32::from_le_bytes(command.args[4..8].try_into().unwrap());
        if command.transfer_length != 0 {
            return Err(CommandStatus::InvalidTransferLength);
        }
        let sector_size = FlashStorage::SECTOR_SIZE;
        if (address % sector_size) != 0 {
            return Err(CommandStatus::BadAlignment);
        }
        if (length % sector_size) != 0 {
            return Err(CommandStatus::BadAlignment);
        }
        if address < FLASH_PROTECT_OFFSET {
            return Err(CommandStatus::NotPermitted);
        }
        if (address + length) as usize > self.flash.capacity() {
            return Err(CommandStatus::InvalidArg);
        }

        let mut start = address;
        let end = address + length;
        while start < end {
            self.flash
                .erase(start, start + sector_size)
                .map_err(|_| CommandStatus::UnknownError)?;
            start += sector_size;
            embassy_futures::yield_now().await;
        }

        Ok(())
    }

    async fn command_read(
        &mut self,
        command: CommandHeader,
        mut write_fn: impl AsyncFnMut(&[u8]) -> Result<(), CommandStatus>,
    ) -> Result<(), CommandStatus> {
        // Validate arguments.
        if command.cmd_size != 8 {
            return Err(CommandStatus::InvalidCmdLength);
        }
        let address = u32::from_le_bytes(command.args[0..4].try_into().unwrap());
        let length = u32::from_le_bytes(command.args[4..8].try_into().unwrap());
        if command.transfer_length != length {
            return Err(CommandStatus::InvalidArg);
        }
        if (address % FlashStorage::WORD_SIZE) != 0 {
            return Err(CommandStatus::BadAlignment);
        }
        if (length % FlashStorage::WORD_SIZE) != 0 {
            return Err(CommandStatus::BadAlignment);
        }
        // TODO: support read from RAM?
        if address < FLASH_BASE_ADDR {
            return Err(CommandStatus::InvalidAddress);
        }
        let mut offset = address - FLASH_BASE_ADDR;
        if (offset + length) as usize > self.flash.capacity() {
            return Err(CommandStatus::InvalidTransferLength);
        }

        let mut buffer = [0u8; 64];
        let end_address = offset + length;
        while offset < end_address {
            let amount = ((end_address - offset) as usize).min(buffer.len());
            let buffer = &mut buffer[0..amount];
            self.flash
                .read(offset, buffer)
                .map_err(|_| CommandStatus::UnknownError)?;
            write_fn(buffer).await?;
            offset += amount as u32;
        }

        Ok(())
    }

    async fn command_write(
        &mut self,
        command: CommandHeader,
        mut read_fn: impl AsyncFnMut(&mut [u8]) -> Result<usize, CommandStatus>,
    ) -> Result<(), CommandStatus> {
        // Validate arguments.
        if command.cmd_size != 8 {
            return Err(CommandStatus::InvalidCmdLength);
        }
        let address = u32::from_le_bytes(command.args[0..4].try_into().unwrap());
        let length = u32::from_le_bytes(command.args[4..8].try_into().unwrap());
        if command.transfer_length != length {
            return Err(CommandStatus::InvalidArg);
        }
        if (address % FlashStorage::WORD_SIZE) != 0 {
            return Err(CommandStatus::BadAlignment);
        }
        if (length % FlashStorage::WORD_SIZE) != 0 {
            return Err(CommandStatus::BadAlignment);
        }
        // TODO: support read from RAM?
        if address < FLASH_BASE_ADDR {
            return Err(CommandStatus::InvalidAddress);
        }
        let offset = address - FLASH_BASE_ADDR;
        if offset < FLASH_PROTECT_OFFSET {
            return Err(CommandStatus::NotPermitted);
        }
        if (offset + length) as usize > self.flash.capacity() {
            return Err(CommandStatus::InvalidTransferLength);
        }

        let mut buffer = [0u8; 64];
        let mut offset = offset;
        let end_address = offset + length;
        while offset < end_address {
            let expected_amount = ((end_address - offset) as usize).min(buffer.len());
            let amount = read_fn(&mut buffer).await?;
            if amount != expected_amount {
                return Err(CommandStatus::InvalidTransferLength);
            }
            let bytes = &buffer[0..amount];
            self.flash
                .write(offset, bytes)
                .map_err(|_| CommandStatus::UnknownError)?;
            offset += amount as u32;
        }

        Ok(())
    }
}

#[derive(Copy, Clone, Debug)]
pub struct CommandState {
    pub token: u32,
    pub status: CommandStatus,
    pub command_id: u8,
    pub in_progress: bool,
}

impl CommandState {
    pub const fn new() -> Self {
        CommandState {
            token: 0,
            status: CommandStatus::Ok,
            command_id: 0,
            in_progress: false,
        }
    }
}

pub fn get_command_state() -> CommandState {
    STATE.lock(|x| x.borrow().clone())
}
