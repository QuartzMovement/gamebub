//! Command line interface

use base64::{prelude::BASE64_STANDARD, Engine};
use esp_idf_svc::hal::units::MegaHertz;

use crate::{
    device::{
        drivers::fpga::{FpgaSpiWordSize, SpiCommand},
        Device,
    },
    input::{GamepadId, InputState},
    kvs, ui, worker,
};

/// Start the CLI thread. Called once during system init.
pub fn start() {
    std::thread::Builder::new()
        .name("CLI".to_string())
        .stack_size(8 * 1024)
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
            "gamepad_connect" => handle_gamepad_connect(args),
            "gamepad_disconnect" => handle_gamepad_disconnect(args),
            "gamepad_data" => handle_gamepad_data(args),
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

/// `>dock_begin,<serial>,<hw version>,<sw version>`: returns `<ok`
fn handle_dock_begin<'a>(mut args: impl Iterator<Item = &'a str>) -> Result<(), String> {
    let dock_serial = args.next().ok_or("missing serial")?;
    let dock_hw_version = args.next().ok_or("missing hw version")?;
    let dock_sw_version = args.next().ok_or("missing sw version")?;
    worker::send(worker::Message::DockState(true));
    println!("<ok");
    log::info!("Dock serial={dock_serial} hw={dock_hw_version} sw={dock_sw_version}");
    Ok(())
}

/// `>gamepad_connect,<slot>,<model name>,<unique id>`: returns `<ok`
fn handle_gamepad_connect<'a>(mut args: impl Iterator<Item = &'a str>) -> Result<(), String> {
    let slot = get_arg_u32(args.next())?;
    let gamepad_model = args.next().ok_or("missing model name")?;
    let gamepad_id = args.next().ok_or("missing unique id")?;
    ui::send(ui::Message::GamepadConnected(GamepadId(slot)));
    println!("<ok");
    log::info!("Gamepad model='{gamepad_model}' id={gamepad_id}");
    Ok(())
}

/// `>gamepad_disconnect,<slot>`: returns `<ok`
fn handle_gamepad_disconnect<'a>(mut args: impl Iterator<Item = &'a str>) -> Result<(), String> {
    let slot = get_arg_u32(args.next())?;
    ui::send(ui::Message::GamepadDisconnected(GamepadId(slot)));
    println!("<ok");
    Ok(())
}

/// `>gamepad_data,<slot>,<data>`: returns `<ok`
fn handle_gamepad_data<'a>(mut args: impl Iterator<Item = &'a str>) -> Result<(), String> {
    let slot = get_arg_u32(args.next())?;
    let data_hex = args.next().ok_or("missing data")?;

    // Data is 128 bits: should be a 32 character string
    if data_hex.len() != 32 {
        return Err("data len".to_string());
    }
    let mut data = [0u8; 16];
    hex::decode_to_slice(data_hex, &mut data).map_err(|_| "invalid hex".to_string())?;

    // (A B X Y) (Up Down Right Left) (System Select Start Capture(?)) (L1 R1 L2 R2 L3 R3)
    let data = InputState {
        // XXX: A/B swapped!
        btn_a: (data[0] & 0x2) != 0,
        btn_b: (data[0] & 0x1) != 0,
        btn_x: (data[0] & 0x4) != 0,
        btn_y: (data[0] & 0x8) != 0,
        btn_up: (data[0] & 0x10) != 0,
        btn_down: (data[0] & 0x20) != 0,
        btn_right: (data[0] & 0x40) != 0,
        btn_left: (data[0] & 0x80) != 0,
        btn_system: (data[1] & 0x1) != 0,
        btn_select: (data[1] & 0x2) != 0,
        btn_start: (data[1] & 0x4) != 0,
        btn_capture: (data[1] & 0x8) != 0,
        btn_power: false,
        btn_vol_up: false,
        btn_vol_down: false,
        btn_l1: (data[1] & 0x10) != 0,
        btn_r1: (data[1] & 0x20) != 0,
        btn_l2: (data[1] & 0x40) != 0,
        btn_r2: (data[1] & 0x80) != 0,
        btn_l3: (data[2] & 0x1) != 0,
        btn_r3: (data[2] & 0x2) != 0,
        axis_lx: i16::from_le_bytes(data[4..6].try_into().unwrap()),
        axis_ly: i16::from_le_bytes(data[6..8].try_into().unwrap()),
        axis_lz: i16::from_le_bytes(data[8..10].try_into().unwrap()),
        axis_rx: i16::from_le_bytes(data[10..12].try_into().unwrap()),
        axis_ry: i16::from_le_bytes(data[12..14].try_into().unwrap()),
        axis_rz: i16::from_le_bytes(data[14..16].try_into().unwrap()),
    };
    ui::send(ui::Message::GamepadInput(GamepadId(slot), data));
    println!("<ok");
    Ok(())
}
