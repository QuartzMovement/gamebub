#![no_std]
#![no_main]
#![deny(
    clippy::mem_forget,
    reason = "mem::forget is generally not safe to do with esp_hal types, especially those \
    holding buffers for the duration of a data transfer."
)]
#![deny(clippy::large_stack_frames)]

use embassy_executor::Spawner;
use esp_hal::clock::CpuClock;
use esp_hal::gpio::{Level, Output, OutputConfig};
use esp_hal::otg_fs::Usb;
use esp_hal::timer::timg::TimerGroup;
use esp_storage::FlashStorage;
use log::info;
use static_cell::StaticCell;

use crate::protocol::Protocol;

mod info;
mod led;
mod protocol;
mod reboot;
mod usb;

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    loop {}
}

extern crate alloc;

// Application descriptor for esp-idf bootloader.
esp_bootloader_esp_idf::esp_app_desc!();

static PROTOCOL: StaticCell<Protocol> = StaticCell::new();

#[allow(
    clippy::large_stack_frames,
    reason = "it's not unusual to allocate larger buffers etc. in main"
)]
#[esp_rtos::main]
async fn main(spawner: Spawner) {
    esp_println::logger::init_logger_from_env();

    let config = esp_hal::Config::default().with_cpu_clock(CpuClock::max());
    let peripherals = esp_hal::init(config);

    esp_alloc::heap_allocator!(#[esp_hal::ram(reclaimed)] size: 73744);

    let timg0 = TimerGroup::new(peripherals.TIMG0);
    esp_rtos::start(timg0.timer0);

    info!("Initialized");

    let led = Output::new(peripherals.GPIO42, Level::Low, OutputConfig::default());
    spawner.spawn(led::blink_task(led)).unwrap();

    spawner.spawn(reboot::task()).unwrap();

    let flash = FlashStorage::new(peripherals.FLASH);
    let protocol = PROTOCOL.init(Protocol::new(flash));

    let usb = Usb::new(peripherals.USB0, peripherals.GPIO20, peripherals.GPIO19);
    usb::setup_usb(spawner.clone(), usb, protocol);
}
