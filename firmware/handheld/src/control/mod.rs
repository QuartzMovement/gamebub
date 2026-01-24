//! USB Vendor Control interface

use crate::device::drivers::usb::control::Request;

/// Handle a control request where data is sent to the host (IN).
pub fn handle_control_in<'a>(request: &Request, _buf: &'a mut [u8]) -> Result<&'a [u8], ()> {
    log::info!(" IN {request:?}");
    Ok(&[])
}

/// Handle a control request where data is sent from the host (OUT).
pub fn handle_control_out(request: &Request, buf: &[u8]) -> Result<(), ()> {
    log::info!("OUT {request:?}: {buf:?}");
    Ok(())
}

/// Handle a control request completion.
pub fn handle_control_complete(request: &Request) {
    log::info!("Completed {request:?}");
}
