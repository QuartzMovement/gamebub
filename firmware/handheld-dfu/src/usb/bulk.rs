use embassy_usb::driver::{Endpoint, EndpointIn, EndpointOut};
use embassy_usb_synopsys_otg::{Endpoint as EspUsbEndpoint, In, Out};

use super::MAX_PACKET_SIZE;

pub struct Bulk {
    ep_out: EspUsbEndpoint<'static, Out>,
    ep_in: EspUsbEndpoint<'static, In>,
}

impl Bulk {
    pub fn new(ep_out: EspUsbEndpoint<'static, Out>, ep_in: EspUsbEndpoint<'static, In>) -> Self {
        Self { ep_out, ep_in }
    }

    pub async fn run(&mut self) -> ! {
        loop {
            self.ep_out.wait_enabled().await;
            // Connected
            let mut total = 0;
            loop {
                let mut data = [0u8; MAX_PACKET_SIZE as usize];
                match self.ep_out.read(&mut data).await {
                    Ok(n) => {
                        total += n;
                        if n < MAX_PACKET_SIZE as usize {
                            let _ = self.ep_in.write(&(total as u32).to_le_bytes()).await;
                            total = 0;
                        }
                    }
                    Err(_) => break,
                }
            }
        }
    }
}
