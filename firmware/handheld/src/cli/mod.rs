//! Command line interface

use base64::{prelude::BASE64_STANDARD, Engine};
use esp_idf_svc::hal::units::MegaHertz;

use crate::{
    device::{
        drivers::fpga::{FpgaSpiWordSize, SpiCommand},
        Device,
    },
    kvs, worker,
};

/// Start the CLI thread. Called once during system init.
pub fn start() {
    std::thread::Builder::new()
        .name("CLI".to_string())
        .stack_size(16 * 1024)
        .spawn(cli_thread)
        .unwrap();
}

fn cli_thread() -> ! {
    let mut line = String::new();
    let stdin = std::io::stdin();
    loop {
        line.clear();
        if let Err(e) = stdin.read_line(&mut line) {
            log::error!("Error reading line: {:?}", e);
        }
        let line = match line.strip_prefix('>') {
            Some(x) => x,
            None => continue,
        };
        let mut line = line.trim_end().split(',');

        let command = line.next().unwrap();
        let args = line;

        let result = match command {
            "get_hwinfo" => handle_get_hwinfo(args),
            "fpga_read" => handle_fpga_read(args),
            "fpga_write" => handle_fpga_write(args),
            "dock_begin" => handle_dock_begin(args),
            _ => Err("unknown command".to_string()),
        };
        if let Err(error) = result {
            println!("<error,{error}");
        }
    }
}

fn get_arg_u32<'a>(arg: Option<&'a str>) -> Result<u32, String> {
    let arg = arg.ok_or("missing arg")?;
    let (arg, base) = match arg.strip_prefix("0x") {
        Some(x) => (x, 16),
        None => (arg, 10),
    };
    u32::from_str_radix(arg, base).map_err(|_| "invalid number".to_string())
}

/// `>get_hwinfo`: returns `<ok,{serial number},{hardware revision}`
fn handle_get_hwinfo<'a>(_args: impl Iterator<Item = &'a str>) -> Result<(), String> {
    println!(
        "<ok,{},{}",
        kvs::keys::DEVICE_SERIAL.get().as_deref().unwrap_or(""),
        kvs::keys::DEVICE_REVISION.get().unwrap_or_default(),
    );
    Ok(())
}

/// `>fpga_read,<addr>,<word size>,<max clock MHz>,<length>`: returns `<ok,{base64 data}`
fn handle_fpga_read<'a>(mut args: impl Iterator<Item = &'a str>) -> Result<(), String> {
    const MAX_READ_LENGTH: u32 = 1024;
    let address = get_arg_u32(args.next())?;
    let word_size = get_arg_u32(args.next())?;
    let max_clock = MegaHertz(get_arg_u32(args.next())?).into();
    let length = get_arg_u32(args.next())?;
    if length > MAX_READ_LENGTH {
        return Err("length too large".to_string());
    }
    let word_size = match word_size {
        8 => FpgaSpiWordSize::Bits8,
        16 => FpgaSpiWordSize::Bits16,
        32 => FpgaSpiWordSize::Bits32,
        64 => FpgaSpiWordSize::Bits64,
        _ => return Err("invalid word size".to_string()),
    };

    let mut buffer = vec![0u8; length as usize];
    let command = SpiCommand {
        word_size,
        byte_swap: true,
        increment_address: true,
    };
    Device::lock()
        .fpga
        .spi_read(Some(max_clock), command, address, &mut buffer)
        .map_err(|_| "read error".to_string())?;
    let output = BASE64_STANDARD.encode(buffer);
    println!("<ok,{}", output);
    Ok(())
}

/// `>fpga_write,<addr>,<word size>,<max clock MHz>,<base64 data>`: returns `<ok`
fn handle_fpga_write<'a>(mut args: impl Iterator<Item = &'a str>) -> Result<(), String> {
    const MAX_WRITE_LENGTH: u32 = 1024;
    let address = get_arg_u32(args.next())?;
    let word_size = get_arg_u32(args.next())?;
    let max_clock = MegaHertz(get_arg_u32(args.next())?).into();
    let data = args.next().ok_or("missing data")?;
    if data.len() > (((MAX_WRITE_LENGTH * 4) / 3) + 4) as usize {
        return Err("length too large".to_string());
    }
    let word_size = match word_size {
        8 => FpgaSpiWordSize::Bits8,
        16 => FpgaSpiWordSize::Bits16,
        32 => FpgaSpiWordSize::Bits32,
        64 => FpgaSpiWordSize::Bits64,
        _ => return Err("invalid word size".to_string()),
    };

    let data = BASE64_STANDARD.decode(data).map_err(|_| "invalid base64")?;
    let command = SpiCommand {
        word_size,
        byte_swap: true,
        increment_address: true,
    };
    Device::lock()
        .fpga
        .spi_write(Some(max_clock), command, address, &data)
        .map_err(|_| "write error".to_string())?;
    println!("<ok");
    Ok(())
}

/// `>dock_begin`: returns `<ok`
fn handle_dock_begin<'a>(_args: impl Iterator<Item = &'a str>) -> Result<(), String> {
    worker::send(worker::Message::DockState(true));
    println!("<ok");
    Ok(())
}
