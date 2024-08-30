//! Worker threads to do background blocking work.

use std::sync::{mpsc, OnceLock};

#[derive(Debug)]
pub enum Message {}

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
    match message {}
}
