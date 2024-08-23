use esp_idf_svc::sys::{self as esp_idf_sys, EspError};

/// Set up and install the TinyUSB driver.
///
/// This takes over the USB PHY, removing the Serial/JTAG built-in device,
/// and instead exposes the MSC device.
pub fn setup_tinyusb() -> Result<(), EspError> {
    // TODO: fill in descriptors
    // TODO: support combined serial CDC device
    let tinyusb_config = esp_idf_sys::tinyusb_config_t {
        string_descriptor: std::ptr::null_mut(),
        string_descriptor_count: 0,
        __bindgen_anon_1: esp_idf_sys::tinyusb_config_t__bindgen_ty_1 {
            device_descriptor: std::ptr::null_mut(),
        },
        __bindgen_anon_2: esp_idf_sys::tinyusb_config_t__bindgen_ty_2 {
            __bindgen_anon_1: esp_idf_sys::tinyusb_config_t__bindgen_ty_2__bindgen_ty_1 {
                configuration_descriptor: std::ptr::null_mut(),
            },
        },
        external_phy: false,
        // TODO: handle self-powered VBUS monitoring
        self_powered: false,
        vbus_monitor_io: 0,
    };
    log::info!("Installing TinyUSB");
    let result = unsafe { esp_idf_sys::tinyusb_driver_install(&tinyusb_config) };
    EspError::convert(result)
}
