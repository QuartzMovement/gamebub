use embassy_executor::Spawner;
use embassy_usb::{Builder, UsbDevice};
use esp_hal::otg_fs::asynch::Config;
use esp_hal::otg_fs::{Usb, asynch::Driver as EspUsbDriver};
use static_cell::{ConstStaticCell, StaticCell};

use crate::usb::control::Control;

mod control;

const MAX_PACKET_SIZE: u16 = 64;

static EP_OUT_BUFFER: ConstStaticCell<[u8; 1024]> = ConstStaticCell::new([0; 1024]);
static CONFIG_DESC: ConstStaticCell<[u8; 256]> = ConstStaticCell::new([0; 256]);
static BOS_DESC: ConstStaticCell<[u8; 256]> = ConstStaticCell::new([0; 256]);
static MSOS_DESC: ConstStaticCell<[u8; 256]> = ConstStaticCell::new([0; 256]);
static CONTROL_BUF: ConstStaticCell<[u8; 64]> = ConstStaticCell::new([0; 64]);
static USB_DEVICE: StaticCell<UsbDevice<'static, EspUsbDriver<'static>>> = StaticCell::new();
static CONTROL: StaticCell<Control> = StaticCell::new();

pub fn setup_usb(spawner: Spawner, usb: Usb<'static>) {
    let config = Config::default();
    let driver = EspUsbDriver::new(usb, EP_OUT_BUFFER.take(), config);

    let mut config = embassy_usb::Config::new(0x1209, 0xB010);
    config.manufacturer = Some("Second Bedroom");
    config.product = Some("Game Bub Handheld DFU");
    config.serial_number = Some("TODO");
    config.self_powered = true;
    config.max_power = 500;
    config.max_packet_size_0 = 64;

    config.device_class = 0xEF;
    config.device_sub_class = 0x02;
    config.device_protocol = 0x01;
    config.composite_with_iads = true;

    let mut builder = Builder::new(
        driver,
        config,
        CONFIG_DESC.take(),
        BOS_DESC.take(),
        MSOS_DESC.take(),
        CONTROL_BUF.take(),
    );

    // Create the vendor interface.
    let mut func = builder.function(0xFF, 0, 0);
    let mut interface = func.interface();
    let interface_number = interface.interface_number();
    let mut alt = interface.alt_setting(0xFF, 0, 0, None);
    let _ep_out = alt.endpoint_bulk_out(None, MAX_PACKET_SIZE);
    let _ep_in = alt.endpoint_bulk_in(None, MAX_PACKET_SIZE);
    drop(func);

    let control = CONTROL.init_with(|| Control::new(interface_number));
    builder.handler(control);

    let usb = USB_DEVICE.init_with(|| builder.build());

    spawner.spawn(usb_task(usb)).unwrap();
}

#[embassy_executor::task]
async fn usb_task(usb: &'static mut UsbDevice<'static, EspUsbDriver<'static>>) -> ! {
    usb.run().await;
}
