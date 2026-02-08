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
pub struct Protocol {}

impl Protocol {
    pub fn new() -> Self {
        Protocol {}
    }

    pub async fn handle(&mut self, command: CommandHeader) -> Result<(), CommandStatus> {
        match command.cmd_id {
            // FLASH_ERASE
            0x03 => {}
            // READ
            0x84 => {}
            // WRITE
            0x05 => {}
            // TODO efuse
            _ => return Err(CommandStatus::UnknownCmd),
        }
        Ok(())
    }
}
