use esp_idf_svc::sys::{self as esp_idf_sys, EspError};
use core::ffi::{c_int, c_void};

#[allow(unused)]
pub fn mount_sdcard() -> Result<(), EspError> {
    const SDMMC_HOST_FLAG_1BIT: u32 = 1 << 0;
    const SDMMC_HOST_FLAG_4BIT: u32 = 1 << 1;
    const SDMMC_HOST_FLAG_8BIT: u32 = 1 << 2; 
    const SDMMC_HOST_FLAG_SPI: u32 = 1 << 3;
    const SDMMC_HOST_FLAG_DDR: u32 = 1 << 4;
    const SDMMC_HOST_FLAG_DEINIT_ARG: u32 = 1 << 5;
    
    const SDMMC_SLOT_NO_CD: esp_idf_sys::gpio_num_t = esp_idf_sys::gpio_num_t_GPIO_NUM_NC;
    const SDMMC_SLOT_NO_WP: esp_idf_sys::gpio_num_t = esp_idf_sys::gpio_num_t_GPIO_NUM_NC;
    const SDMMC_SLOT_WIDTH_DEFAULT: u8 = 0;

    let host_config = esp_idf_sys::sdmmc_host_t {
        flags: SDMMC_HOST_FLAG_1BIT | SDMMC_HOST_FLAG_4BIT | SDMMC_HOST_FLAG_DDR,
        slot: 1,
        max_freq_khz: esp_idf_sys::SDMMC_FREQ_HIGHSPEED as c_int,
        io_voltage: 3.3,
        init: Some(esp_idf_sys::sdmmc_host_init),
        set_bus_width: Some(esp_idf_sys::sdmmc_host_set_bus_width),
        get_bus_width: Some(esp_idf_sys::sdmmc_host_get_slot_width),
        set_bus_ddr_mode: Some(esp_idf_sys::sdmmc_host_set_bus_ddr_mode),
        set_card_clk: Some(esp_idf_sys::sdmmc_host_set_card_clk),
        set_cclk_always_on: Some(esp_idf_sys::sdmmc_host_set_cclk_always_on),
        do_transaction: Some(esp_idf_sys::sdmmc_host_do_transaction),
        io_int_enable: Some(esp_idf_sys::sdmmc_host_io_int_enable),
        io_int_wait: Some(esp_idf_sys::sdmmc_host_io_int_wait),
        command_timeout_ms: 0,
        get_real_freq: Some(esp_idf_sys::sdmmc_host_get_real_freq),
        __bindgen_anon_1: esp_idf_sys::sdmmc_host_t__bindgen_ty_1 {
            deinit: Some(esp_idf_sys::sdmmc_host_deinit),
        },
    };

    let slot_config = esp_idf_sys::sdmmc_slot_config_t {
        __bindgen_anon_1: esp_idf_sys::sdmmc_slot_config_t__bindgen_ty_1 {
            gpio_cd: SDMMC_SLOT_NO_CD,
        },
        __bindgen_anon_2: esp_idf_sys::sdmmc_slot_config_t__bindgen_ty_2 {
            gpio_wp: SDMMC_SLOT_NO_WP,
        },
        width: 4,
        flags: 0,
        clk: esp_idf_sys::gpio_num_t_GPIO_NUM_45,
        cmd: esp_idf_sys::gpio_num_t_GPIO_NUM_48,
        d0: esp_idf_sys::gpio_num_t_GPIO_NUM_35,
        d1: esp_idf_sys::gpio_num_t_GPIO_NUM_36,
        d2: esp_idf_sys::gpio_num_t_GPIO_NUM_21,
        d3: esp_idf_sys::gpio_num_t_GPIO_NUM_47,
        d4: esp_idf_sys::gpio_num_t_GPIO_NUM_NC,
        d5: esp_idf_sys::gpio_num_t_GPIO_NUM_NC,
        d6: esp_idf_sys::gpio_num_t_GPIO_NUM_NC,
        d7: esp_idf_sys::gpio_num_t_GPIO_NUM_NC,
    };

    let mount_config = esp_idf_sys::esp_vfs_fat_sdmmc_mount_config_t {
        format_if_mount_failed: false,
        max_files: 4,
        allocation_unit_size: 16 * 1024,
        disk_status_check_enable: false,
    };

    const MOUNT_POINT: &[u8] = b"/sdcard\0";

    log::info!("Mounting sdcard on slot {}", host_config.slot);
    let mut card: *mut esp_idf_sys::sdmmc_card_t = std::ptr::null_mut();
    let sdmmc_mount_result = unsafe {
        esp_idf_sys::esp_vfs_fat_sdmmc_mount(
            MOUNT_POINT.as_ptr() as *const i8,
            &host_config,
            &slot_config as *const esp_idf_sys::sdmmc_slot_config_t as *const c_void,
            &mount_config,
            &mut card,
        )
    };
    EspError::convert(sdmmc_mount_result)
}
