use std::{cell::RefCell, rc::Rc};

use super::super::slint::{Backend, BatteryInfo};
use slint::ComponentHandle;

use crate::{device::Device, worker};

use super::UiState;

impl UiState {
    /// Set up the "Tools" screen.
    pub(super) fn setup_tools(&mut self, _state: &Rc<RefCell<UiState>>, _device: &mut Device) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();

        backend.on_tools_start_usb_drive(move || {
            crate::device::drivers::usb::setup_tinyusb_sdcard().expect("USB setup failed");
        });
        backend.on_tools_end_usb_drive(move || {
            crate::device::drivers::usb::teardown_tinyusb().expect("USB teardown failed");
            Device::lock().reboot();
        });

        backend.on_tools_start_cart_reader(move || {
            // The boot bitstream contains CartridgeUtility
            worker::send(worker::Message::EnsureBootBitstream);
            crate::device::drivers::usb::setup_tinyusb_cart_reader().expect("USB setup failed");
        });
        backend.on_tools_end_cart_reader(move || {
            crate::device::drivers::usb::teardown_tinyusb().expect("USB teardown failed");
            Device::lock().reboot();
        });

        backend.on_tools_get_battery_info(move || {
            let mut device = Device::lock();
            BatteryInfo {
                is_charging: device.get_battery_is_charging(),
                level: device.fuel_gauge.get_battery_level().unwrap_or(f32::NAN),
                vbus_pgood: device.get_vbus_pgood(),
                voltage: device.fuel_gauge.get_battery_voltage().unwrap_or(f32::NAN),
            }
        })
    }
}
