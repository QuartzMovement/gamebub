use esp_idf_svc::sys::{self as sys};
use std::ffi::c_char;

const USB_VID: u16 = 0x1209;
const USB_PID: u16 = 0xB010;

const CONFIG_DESCRIPTOR_LEN: usize = 9;
const MSC_DESCRIPTOR_LEN: usize = 23;

/// Helper for generating TinyUSB descriptors
pub struct Builder {
    /// String descriptors
    strings: Vec<*const c_char>,
    /// Device descriptor
    device: sys::tusb_desc_device_t,
    /// Configuration descriptors
    configuration: Vec<u8>,

    /// Interface count
    interfaces: u8,
    /// Endpoint count
    endpoints: u8,
}

#[allow(unused)]
impl Builder {
    pub fn new() -> Builder {
        Builder {
            strings: vec![
                // 0: English
                c"\x09\x04".as_ptr(),
                // 1: Manufacturer
                c"Game Bub".as_ptr(),
                // 2: Product
                c"Handheld".as_ptr(),
                // 3: Serial
                c"BUB".as_ptr(),
                // 4: CDC Interface
                c"CDC Device".as_ptr(),
                // 5: MSC interface
                c"MSC Device".as_ptr(),
                // NULL end
                std::ptr::null(),
            ],
            device: sys::tusb_desc_device_t {
                bLength: std::mem::size_of::<sys::tusb_desc_device_t>() as u8,
                bDescriptorType: sys::tusb_desc_type_t_TUSB_DESC_DEVICE as u8,
                bcdUSB: 0x0200, // USB 2.0
                bDeviceClass: 0x00,
                bDeviceSubClass: 0x00,
                bDeviceProtocol: 0x00,
                bMaxPacketSize0: sys::CFG_TUD_ENDPOINT0_SIZE as u8,
                idVendor: USB_VID,
                idProduct: USB_PID,
                bcdDevice: 0x0100,
                iManufacturer: 0x01, // string index 1
                iProduct: 0x02,      // string index 2
                iSerialNumber: 0x03, // string index 3
                bNumConfigurations: 0x01,
            },
            configuration: vec![0u8; CONFIG_DESCRIPTOR_LEN],
            interfaces: 0,
            endpoints: 0,
        }
    }

    pub fn add_msc(&mut self) -> u8 {
        // Assign endpoint and interface numbers
        let itfnum = self.interfaces;
        let stridx = 4; // string index 5: MSC device
        let ep_out = self.endpoints + 1;
        let ep_in = 0x80 | ep_out;
        let ep_size = 64;
        self.interfaces += 1;
        self.endpoints += 1;

        // Build it
        let descriptor = [
            // Interface
            9,
            sys::tusb_desc_type_t_TUSB_DESC_INTERFACE as u8,
            itfnum,
            0,
            2,
            sys::tusb_class_code_t_TUSB_CLASS_MSC as u8,
            0x6,
            0x50,
            stridx,
            // Endpoint Out,
            7,
            sys::tusb_desc_type_t_TUSB_DESC_ENDPOINT as u8,
            ep_out,
            sys::tusb_xfer_type_t_TUSB_XFER_BULK as u8,
            (ep_size & 0xFF) as u8,
            ((ep_size >> 8) & 0xFF) as u8,
            0,
            // Endpoint In,
            7,
            sys::tusb_desc_type_t_TUSB_DESC_ENDPOINT as u8,
            ep_in,
            sys::tusb_xfer_type_t_TUSB_XFER_BULK as u8,
            (ep_size & 0xFF) as u8,
            ((ep_size >> 8) & 0xFF) as u8,
            0,
        ];
        assert!(descriptor.len() == MSC_DESCRIPTOR_LEN);
        self.configuration.extend_from_slice(&descriptor);
        itfnum
    }

    fn finalize_config_descriptor(&mut self) {
        let config_num = 1;
        let stridx = 0;
        let total_len = self.configuration.len() as u16;
        let attribute = 0x80 | (sys::TUSB_DESC_CONFIG_ATT_REMOTE_WAKEUP as u8); // TODO: also SELF_POWERED?
        let power_ma = 100;

        let descriptor = [
            9,
            sys::tusb_desc_type_t_TUSB_DESC_CONFIGURATION as u8,
            (total_len & 0xFF) as u8,
            ((total_len >> 8) & 0xFF) as u8,
            self.interfaces,
            config_num,
            stridx,
            attribute,
            (power_ma / 2) as u8,
        ];
        assert!(descriptor.len() == CONFIG_DESCRIPTOR_LEN);
        self.configuration
            .splice(0..CONFIG_DESCRIPTOR_LEN, descriptor.into_iter());
    }

    pub fn build(mut self) -> Descriptors {
        self.finalize_config_descriptor();
        Descriptors {
            strings: self.strings.into_boxed_slice(),
            device: self.device,
            configuration: self.configuration.into_boxed_slice(),
        }
    }
}

pub struct Descriptors {
    strings: Box<[*const c_char]>,
    device: sys::tusb_desc_device_t,
    configuration: Box<[u8]>,
}

impl Descriptors {
    pub fn tinyusb_config(&self) -> sys::tinyusb_config_t {
        sys::tinyusb_config_t {
            string_descriptor: self.strings.as_ptr().cast_mut(),
            string_descriptor_count: (self.strings.len() - 1) as i32, // exclude NULL entry
            __bindgen_anon_1: sys::tinyusb_config_t__bindgen_ty_1 {
                device_descriptor: &self.device,
            },
            __bindgen_anon_2: sys::tinyusb_config_t__bindgen_ty_2 {
                __bindgen_anon_1: sys::tinyusb_config_t__bindgen_ty_2__bindgen_ty_1 {
                    configuration_descriptor: self.configuration.as_ptr(),
                },
            },
            external_phy: false,
            // TODO: handle self-powered VBUS monitoring
            self_powered: false,
            vbus_monitor_io: 0,
        }
    }
}
