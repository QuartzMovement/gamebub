//! Worker threads to do background blocking work.

use std::sync::{mpsc, OnceLock};

use crate::device::{drivers::fuel_gauge, Device};
use crate::ui;

#[derive(Debug)]
pub enum Message {
    FpgaIrq(u32),
    FuelGaugeAlert(fuel_gauge::Alert),
    HeadphoneState(bool),
}

/// Send a message to the worker threads.
pub fn send(message: Message) {
    SENDER.get().unwrap().send(message).unwrap();
}

/// Start the worker threadpool. Called once during system init. Panics if called twice.
pub fn start() {
    let (sender, receiver) = mpsc::channel::<Message>();
    SENDER.set(sender).expect("Worker already initialized");

    std::thread::spawn(move || {
        while let Ok(message) = receiver.recv() {
            log::debug!("Dispatch {:?}", message);
            dispatch(message);
        }
    });
}

static SENDER: OnceLock<mpsc::Sender<Message>> = OnceLock::new();

fn dispatch(message: Message) {
    match message {
        Message::FpgaIrq(irq_mask) => {
            if irq_mask & 0b1 != 0 {
                // Module vblank
                if let Some(bitstream) = crate::bitstream::current().get() {
                    bitstream.on_vblank_irq();
                }
            }
        }
        Message::FuelGaugeAlert(fuel_gauge::Alert::ChargeChange) => {
            let level = Device::lock().fuel_gauge.get_battery_level().unwrap_or(0.0);
            ui::send(ui::Message::BatteryStatus { level });
        }
        Message::HeadphoneState(has_headphones) => {
            log::info!("Headphone detection: {}", has_headphones);
            let mut device = Device::lock();
            device.dac.set_headphones_enabled(has_headphones).unwrap();
            device.dac.set_speakers_enabled(!has_headphones).unwrap();
        }
        _ => {
            log::warn!("Unhandled message: {:?}", message);
        }
    }
}
