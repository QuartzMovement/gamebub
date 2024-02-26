use std::{num::NonZeroU32, sync::Arc};

use esp_idf_svc::{
    hal::{
        gpio::{InputMode, InterruptType, Pin, PinDriver},
        task::notification::{Notification, Notifier},
    },
    sys::EspError,
};

use super::Device;

const FLAG_MCU_IRQ: NonZeroU32 = unsafe { NonZeroU32::new_unchecked(1) };
const FLAG_HOME: NonZeroU32 = unsafe { NonZeroU32::new_unchecked(2) };
const FLAG_POWER: NonZeroU32 = unsafe { NonZeroU32::new_unchecked(4) };
const FLAG_VOL_UP: NonZeroU32 = unsafe { NonZeroU32::new_unchecked(8) };
const FLAG_VOL_DOWN: NonZeroU32 = unsafe { NonZeroU32::new_unchecked(16) };

fn setup_gpio_interrupt(
    pin: &mut PinDriver<'_, impl Pin, impl InputMode>,
    interrupt_type: InterruptType,
    notifier: Arc<Notifier>,
    flags: NonZeroU32,
) -> Result<(), EspError> {
    // SAFETY: only ISR-safe FreeRTOS functions will be called (task notify).
    unsafe {
        pin.subscribe(move || {
            notifier.notify_and_yield(flags);
        })?;
    }
    pin.set_interrupt_type(interrupt_type)?;
    pin.enable_interrupt()?;
    Ok(())
}

impl Device<'_> {
    /// Setup interrupts on the Device interrupt sources:
    ///
    /// * Volume up, volume down, home, and power buttons
    /// * Shared MCU_IRQ line
    pub(super) fn setup_interrupts() {
        // Setup interrupts (testing).
        std::thread::spawn(|| {
            let notification = Notification::new();

            let event_sender = {
                let device = &mut Device::get().lock().unwrap();
                setup_gpio_interrupt(
                    &mut device.button_home,
                    InterruptType::AnyEdge,
                    notification.notifier(),
                    FLAG_HOME,
                )
                .unwrap();
                setup_gpio_interrupt(
                    &mut device.button_power,
                    InterruptType::AnyEdge,
                    notification.notifier(),
                    FLAG_POWER,
                )
                .unwrap();
                setup_gpio_interrupt(
                    &mut device.button_vol_up,
                    InterruptType::AnyEdge,
                    notification.notifier(),
                    FLAG_VOL_UP,
                )
                .unwrap();
                setup_gpio_interrupt(
                    &mut device.button_vol_down,
                    InterruptType::AnyEdge,
                    notification.notifier(),
                    FLAG_VOL_DOWN,
                )
                .unwrap();
                setup_gpio_interrupt(
                    &mut device.pin_irq,
                    InterruptType::LowLevel,
                    notification.notifier(),
                    FLAG_MCU_IRQ,
                )
                .unwrap();

                device.event_sender.clone()
            };

            loop {
                let flags = match notification.wait(esp_idf_svc::hal::delay::BLOCK) {
                    Some(flags) => flags.get(),
                    _ => continue,
                };

                let mut device = Device::get().lock().unwrap();

                if (flags & FLAG_HOME.get()) != 0 {
                    let _ = device.button_home.enable_interrupt();
                }
                if (flags & FLAG_POWER.get()) != 0 {
                    let _ = device.button_power.enable_interrupt();
                }
                if (flags & FLAG_VOL_UP.get()) != 0 {
                    let _ = device.button_vol_up.enable_interrupt();
                }
                if (flags & FLAG_VOL_DOWN.get()) != 0 {
                    let _ = device.button_vol_down.enable_interrupt();
                }

                let buttons = device.read_button_state().unwrap();
                let _ = event_sender.send(super::Event::Button(buttons));

                if (flags & FLAG_MCU_IRQ.get()) != 0 {
                    log::info!("Interrupt: MCU_IRQ");
                    // N.B. important to read buttons above to clear i/o expander interrupt

                    // TODO handle other possible interrupt sources, including FPGA

                    let _ = device.pin_irq.enable_interrupt();
                }
            }
        });
    }
}
