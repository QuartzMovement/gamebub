use std::io::{Read, Write};

use crate::device::drivers::usb::CdcStream;

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
}

impl CartBackup {
    fn new(stream: CdcStream) -> Self {
        CartBackup { stream }
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
        loop {
            let data = self.read_one()?;
            match data {
                0xA1 => {
                    // QUERY_FW_INFO
                    log::info!("QUERY_FW_INFO");
                    self.write_ack()?;
                }
                _ => {
                    log::info!("Unknown command 0x{:X}", data);
                }
            }
            self.stream.flush()?;
        }
    }
}
