use embassy_usb::{
    Handler,
    control::{InResponse, OutResponse, Recipient, Request, RequestType},
    types::InterfaceNumber,
};

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
                // TODO kind
                // TODO defer this for some time to allow the host to see response
                esp_hal::system::software_reset();
            }
            REQ_ENABLE_DEBUG => {
                // TODO
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
                for x in &mut buf[0..24] {
                    *x = 0;
                }
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
