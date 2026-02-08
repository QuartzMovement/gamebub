use embassy_usb::{
    Handler,
    control::{InResponse, OutResponse, Recipient, Request, RequestType},
    types::InterfaceNumber,
};

use crate::info;

const REQ_GET_INFO: u8 = 0;
const REQ_REBOOT: u8 = 1;
const REQ_ENABLE_DEBUG: u8 = 2;

pub struct Control {
    interface_number: InterfaceNumber,
}

impl Handler for Control {
    fn control_out(
        &mut self,
        req: embassy_usb::control::Request,
        _data: &[u8],
    ) -> Option<embassy_usb::control::OutResponse> {
        self.check_request(&req)?;
        match req.request {
            REQ_REBOOT => {
                match req.value {
                    1 => crate::reboot::reboot(),
                    2 => crate::reboot::reboot_dfu(),
                    _ => return Some(OutResponse::Rejected),
                }
                Some(OutResponse::Accepted)
            }
            REQ_ENABLE_DEBUG => {
                crate::reboot::enable_debug();
                Some(OutResponse::Accepted)
            }
            _ => Some(OutResponse::Rejected),
        }
    }

    fn control_in<'a>(
        &'a mut self,
        req: embassy_usb::control::Request,
        buf: &'a mut [u8],
    ) -> Option<embassy_usb::control::InResponse<'a>> {
        self.check_request(&req)?;
        match req.request {
            REQ_GET_INFO => {
                assert!(buf.len() >= 24);
                buf[0..4].copy_from_slice(&0u32.to_le_bytes());
                buf[4..8].copy_from_slice(&info::SerialNumber::get().0.to_le_bytes());
                buf[8..12].copy_from_slice(&info::HardwareVersion::get().as_u32().to_le_bytes());
                // TODO firmware version
                buf[12..16].copy_from_slice(&0u32.to_le_bytes());
                buf[16..20].copy_from_slice(&0u32.to_le_bytes());
                buf[20..24].copy_from_slice(&0u32.to_le_bytes());
                Some(InResponse::Accepted(&buf[0..24]))
            }
            _ => Some(InResponse::Rejected),
        }
    }
}

impl Control {
    pub fn new(interface_number: InterfaceNumber) -> Self {
        Self { interface_number }
    }

    fn check_request(&self, req: &Request) -> Option<()> {
        let required = (
            RequestType::Vendor,
            Recipient::Interface,
            self.interface_number.0 as u16,
        );
        let actual = (req.request_type, req.recipient, req.index);
        (actual == required).then_some(())
    }
}
