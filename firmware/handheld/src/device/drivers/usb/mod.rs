use esp_idf_svc::sys::{self as esp_idf_sys, EspError};

mod cdc_stream;
mod descriptors;

pub use cdc_stream::CdcStream;

/// Set up and install the TinyUSB driver.
///
/// This takes over the USB PHY, removing the Serial/JTAG built-in device,
/// and instead exposes the MSC device.
pub fn setup_tinyusb_sdcard() -> Result<(), EspError> {
    log::info!("Installing TinyUSB: sdcard");
    let mut descriptors = descriptors::Builder::new();
    descriptors.add_msc();
    setup_tinyusb(descriptors)
}

/// Set up and install the cart reader (USB CDC) device.
pub fn setup_tinyusb_cart_reader() -> Result<(), EspError> {
    log::info!("Installing TinyUSB: cart reader");
    let mut descriptors = descriptors::Builder::new();
    descriptors.add_cdc();
    descriptors.add_cdc();
    setup_tinyusb(descriptors)?;

    // Setup first CDC interface (console)
    let acm_config = esp_idf_sys::tinyusb_config_cdcacm_t {
        usb_dev: 0,          // TINYUSB_USBDEV_0
        cdc_port: 0,         // TINYUSB_CDC_ACM_0
        rx_unread_buf_sz: 0, // unused
        callback_rx: None,
        callback_rx_wanted_char: None,
        callback_line_state_changed: None,
        callback_line_coding_changed: None,
    };
    let result = unsafe { esp_idf_sys::tusb_cdc_acm_init(&acm_config) };
    EspError::convert(result)?;
    let result = unsafe {
        esp_idf_sys::esp_tusb_init_console(0 /* TINYUSB_CDC_ACM_0 */)
    };
    EspError::convert(result)?;

    // Setup second CDC interface
    let acm_config = esp_idf_sys::tinyusb_config_cdcacm_t {
        usb_dev: 0,          // TINYUSB_USBDEV_0
        cdc_port: 1,         // TINYUSB_CDC_ACM_1
        rx_unread_buf_sz: 0, // unused
        callback_rx: Some(cdc_stream::cdc_stream_callback_rx),
        callback_rx_wanted_char: None,
        callback_line_state_changed: None,
        callback_line_coding_changed: None,
    };
    let result = unsafe { esp_idf_sys::tusb_cdc_acm_init(&acm_config) };
    EspError::convert(result)?;

    Ok(())
}

fn setup_tinyusb(descriptors: descriptors::Builder) -> Result<(), EspError> {
    let descriptors = Box::new(descriptors.build());
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
