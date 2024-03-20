use enum_map::{Enum, EnumMap};

#[derive(Copy, Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Enum)]
pub enum Button {
    A,
    B,
    X,
    Y,
    Up,
    Down,
    Left,
    Right,
    L,
    R,
    Start,
    Select,
    Home,
    VolUp,
    VolDown,
    Power,
}

pub type ButtonMap = EnumMap<Button, bool>;

#[derive(Copy, Clone, Debug)]
pub enum ButtonEvent {
    Pressed(Button),
    Released(Button),
}

/// Detects [`ButtonEvent`]s by diffing the state of buttons.
pub struct ButtonEventDetector {
    state: ButtonMap,
}

impl ButtonEventDetector {
    pub fn new() -> Self {
        ButtonEventDetector {
            state: ButtonMap::default(),
        }
    }

    pub fn update(&mut self, new_state: ButtonMap) -> impl Iterator<Item = ButtonEvent> {
        let prev = std::mem::replace(&mut self.state, new_state);
        prev.into_iter()
            .filter_map(move |(button, curr)| match (curr, new_state[button]) {
                (false, true) => Some(ButtonEvent::Pressed(button)),
                (true, false) => Some(ButtonEvent::Released(button)),
                _ => None,
            })
    }
}
