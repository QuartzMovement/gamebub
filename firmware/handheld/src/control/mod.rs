//! USB Vendor Control interface

use crate::{
    device::drivers::usb::control::{Recipient, Request},
    hwinfo, worker,
};

/// Get device status and info
const REQUEST_GET_INFO: u8 = 0;
/// Enable USB Serial/JTAG
const REQUEST_ENABLE_DEBUG: u8 = 2;

/// Handle a control request where data is sent to the host (IN).
pub fn handle_control_in<'a>(request: &Request, buf: &'a mut [u8]) -> Result<&'a [u8], ()> {
    if request.recipient != Recipient::Device {
        return Err(());
    }

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
pub fn handle_control_out(request: &Request, _buf: &[u8]) -> Result<(), ()> {
    if request.recipient != Recipient::Device {
        return Err(());
    }

    match request.request {
        REQUEST_ENABLE_DEBUG => Ok(()),
        _ => Err(()),
    }
}

/// Handle a control request completion. Not needed for most requests.
pub fn handle_control_complete(request: &Request) {
    match request.request {
        REQUEST_ENABLE_DEBUG => {
            // This will cause re-enumeration, so we wait until this function
            // because the Ack has already been sent to the host.
            log::info!("Enabling USB Serial / JTAG");
            worker::send(worker::Message::EnableUsbSerialJtag);
        }
        _ => {}
    }
}
