use esp_idf_svc::sys::{self as esp_idf_sys, EspError};

mod descriptors;

/// Set up and install the TinyUSB driver.
///
/// This takes over the USB PHY, removing the Serial/JTAG built-in device,
/// and instead exposes the MSC device.
pub fn setup_tinyusb() -> Result<(), EspError> {
    let mut descriptors = descriptors::Builder::new();
    descriptors.add_msc();
    let descriptors = Box::new(descriptors.build());
    log::info!("Installing TinyUSB");
    let tinyusb_config = descriptors.tinyusb_config();
    Box::leak(descriptors); // TinyUSB will keep using the descriptors
    let result = unsafe { esp_idf_sys::tinyusb_driver_install(&tinyusb_config) };
    EspError::convert(result)
}

/// Tear down / uninstall the TinyUSB driver, re-exposing to Serial/JTAG USB device.
pub fn teardown_tinyusb() -> Result<(), EspError> {
    let result = unsafe { esp_idf_sys::tinyusb_driver_uninstall() };
    EspError::convert(result)?;

    // Re-initialize Serial/JTAG.
    let phy_config = esp_idf_sys::usb_phy_config_t {
        controller: esp_idf_sys::usb_phy_controller_t_USB_PHY_CTRL_SERIAL_JTAG,
        target: esp_idf_sys::usb_phy_target_t_USB_PHY_TARGET_INT,
        otg_mode: 0,
        otg_speed: 0,
        ext_io_conf: std::ptr::null(),
        otg_io_conf: std::ptr::null(),
    };
    let mut jtag_phy: esp_idf_sys::usb_phy_handle_t = std::ptr::null_mut();
    let result = unsafe { esp_idf_sys::usb_new_phy(&phy_config, &mut jtag_phy) };
    EspError::convert(result)?;

    log::info!("Re-initialized Serial/JTAG USB device");
    Ok(())
}
