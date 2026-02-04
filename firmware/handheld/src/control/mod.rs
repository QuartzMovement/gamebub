//! USB Vendor Control interface

use crate::{
    device::drivers::usb::control::Request,
    hwinfo,
    input::{GamepadId, InputState},
    ui, worker,
};

/// Get device status and info
const REQUEST_GET_INFO: u8 = 0;
const REQUEST_REBOOT: u8 = 1;
/// Enable USB Serial/JTAG
const REQUEST_ENABLE_DEBUG: u8 = 2;

const REQUEST_DOCK_BEGIN: u8 = 3;

const REQUEST_GAMEPAD_CONNECT: u8 = 4;
const REQUEST_GAMEPAD_DISCONNECT: u8 = 5;
const REQUEST_GAMEPAD_DATA: u8 = 6;

/// Handle a control request where data is sent to the host (IN).
pub fn handle_control_in<'a>(request: &Request, buf: &'a mut [u8]) -> Result<&'a [u8], ()> {
    match request.request {
        REQUEST_GET_INFO => {
            buf[0..4].copy_from_slice(&0u32.to_le_bytes());
            buf[4..8].copy_from_slice(&hwinfo::get_serial_number().0.to_le_bytes());
            buf[8..12].copy_from_slice(&hwinfo::get_hardware_version().as_u32().to_le_bytes());
            buf[12] = 0;
            buf[13] = env!("CARGO_PKG_VERSION_PATCH").parse().unwrap_or_default();
            buf[14] = env!("CARGO_PKG_VERSION_MINOR").parse().unwrap_or_default();
            buf[15] = env!("CARGO_PKG_VERSION_MAJOR").parse().unwrap_or_default();
            Ok(&buf[0..16])
        }
        _ => Err(()),
    }
}

/// Handle a control request where data is sent from the host (OUT).
pub fn handle_control_out(request: &Request, buf: &[u8]) -> Result<(), ()> {
    match request.request {
        REQUEST_REBOOT => Ok(()),
        REQUEST_ENABLE_DEBUG => Ok(()),
        REQUEST_DOCK_BEGIN => {
            if buf.len() < 16 {
                return Err(());
            }
            let serial_number = u32::from_le_bytes(buf[4..8].try_into().unwrap());
            let hardware_version = u32::from_le_bytes(buf[8..12].try_into().unwrap());
            let firmware_version = u32::from_le_bytes(buf[12..16].try_into().unwrap());
            log::info!("Dock: serial={serial_number:08X} hw={hardware_version:08X} fw={firmware_version:08X}");
            worker::send(worker::Message::DockState(true));
            Ok(())
        }
        REQUEST_GAMEPAD_CONNECT => {
            // 4 byte: slot
            // 4 byte: reserved
            // 8 byte: gamepad device ID
            // 32 byte: model name (\0 terminated)
            if buf.len() < 48 {
                return Err(());
            }
            let slot = u32::from_le_bytes(buf[0..4].try_into().unwrap());
            let device_id: [u8; 8] = buf[8..16].try_into().unwrap();
            let model = str::from_utf8(&buf[16..48])
                .unwrap_or_default()
                .trim_end_matches('\0');
            ui::send(ui::Message::GamepadConnected(GamepadId(slot)));
            ui::send(ui::Message::Notification(ui::Notification::new_short(
                "Gamepad connected".to_string(),
            )));
            log::info!("Gamepad model='{model}' id={device_id:?}");
            Ok(())
        }
        REQUEST_GAMEPAD_DISCONNECT => {
            // 4 byte: slot
            if buf.len() < 4 {
                return Err(());
            }
            let slot = u32::from_le_bytes(buf[0..4].try_into().unwrap());
            ui::send(ui::Message::GamepadDisconnected(GamepadId(slot)));
            ui::send(ui::Message::Notification(ui::Notification::new_short(
                "Gamepad disconnected".to_string(),
            )));
            Ok(())
        }
        REQUEST_GAMEPAD_DATA => {
            // 4 byte: slot
            // 16 byte: gamepad data
            if buf.len() < 20 {
                return Err(());
            }
            let slot = u32::from_le_bytes(buf[0..4].try_into().unwrap());
            // (A B X Y) (Up Down Right Left) (System Select Start Capture(?)) (L1 R1 L2 R2 L3 R3)
            let data = &buf[4..20];
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
            Ok(())
        }
        _ => Err(()),
    }
}

/// Handle a control request completion. Not needed for most requests.
pub fn handle_control_complete(request: &Request) {
    match request.request {
        REQUEST_REBOOT => match request.value {
            1 => worker::send(worker::Message::Reboot),
            2 => worker::send(worker::Message::RebootBootloader),
            _ => {}
        },
        REQUEST_ENABLE_DEBUG => {
            // This will cause re-enumeration, so we wait until this function
            // because the Ack has already been sent to the host.
            log::info!("Enabling USB Serial / JTAG");
            worker::send(worker::Message::EnableUsbSerialJtag);
        }
        _ => {}
    }
}
