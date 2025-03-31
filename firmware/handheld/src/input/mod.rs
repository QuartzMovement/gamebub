use std::sync::{LazyLock, Mutex, MutexGuard};

use crate::ui;

#[derive(Debug, Clone, Default)]
pub struct InputState {
    pub btn_a: bool,
    pub btn_b: bool,
    pub btn_x: bool,
    pub btn_y: bool,

    pub btn_up: bool,
    pub btn_down: bool,
    pub btn_right: bool,
    pub btn_left: bool,

    pub btn_system: bool,
    pub btn_select: bool,
    pub btn_start: bool,
    pub btn_capture: bool,

    pub btn_vol_up: bool,
    pub btn_vol_down: bool,
    pub btn_power: bool,

    pub btn_l1: bool,
    pub btn_r1: bool,
    pub btn_l2: bool,
    pub btn_r2: bool,
    pub btn_l3: bool,
    pub btn_r3: bool,

    pub axis_lx: i16,
    pub axis_ly: i16,
    pub axis_lz: i16,
    pub axis_rx: i16,
    pub axis_ry: i16,
    pub axis_rz: i16,
}

static INPUT_MANAGER: LazyLock<Mutex<InputManager>> =
    LazyLock::new(|| Mutex::new(InputManager::default()));

#[derive(Default)]
pub struct InputManager {
    /// The state of the internal buttons.
    internal_state: InputState,
}

impl InputManager {
    pub fn lock() -> MutexGuard<'static, Self> {
        // TODO: can we just only do this from the worker thread?
        INPUT_MANAGER.lock().unwrap()
    }

    /// Update the state of the internal buttons.
    pub fn update_state(&mut self, state: InputState) {
        self.internal_state = state;
        self.send_event();
    }

    /// Send an input event to the UI thread, if needed.
    ///
    /// Also update the FPGA, if needed?
    fn send_event(&self) {
        let state = &self.internal_state;

        use ui::buttons::Button;
        let buttons = enum_map::enum_map! {
            Button::A => state.btn_a,
            Button::B => state.btn_b,
            Button::X => state.btn_x,
            Button::Y => state.btn_y,
            Button::Up => state.btn_up,
            Button::Down => state.btn_down,
            Button::Left => state.btn_left,
            Button::Right => state.btn_right,
            Button::Start => state.btn_start,
            Button::Select => state.btn_select,
            Button::L => state.btn_l1,
            Button::R => state.btn_r1,
            Button::Home => state.btn_system,
            Button::VolUp => state.btn_vol_up,
            Button::VolDown => state.btn_vol_down,
            Button::Power => state.btn_power,
        };
        ui::send(ui::Message::Button(buttons));
        // TODO: update FPGA if using controller?
    }
}
