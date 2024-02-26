#[derive(Copy, Clone, Debug, Default)]
pub struct ButtonState {
    pub a: bool,
    pub b: bool,
    pub x: bool,
    pub y: bool,
    pub up: bool,
    pub down: bool,
    pub left: bool,
    pub right: bool,
    pub l: bool,
    pub r: bool,
    pub start: bool,
    pub select: bool,
    pub home: bool,
    pub vol_up: bool,
    pub vol_down: bool,
    pub power: bool,
}

#[derive(Copy, Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
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

#[derive(Copy, Clone, Debug)]
pub enum ButtonEvent {
    Pressed(Button),
    Released(Button),
}

/// Detects [`ButtonEvent`]s by diffing the state of buttons.
pub struct ButtonEventDetector {
    state: ButtonState,
}

impl ButtonEventDetector {
    pub fn new() -> Self {
        ButtonEventDetector {
            state: ButtonState::default(),
        }
    }

    pub fn update(&mut self, new_state: ButtonState) -> impl Iterator<Item = ButtonEvent> {
        ButtonEventIterator {
            button: Some(Button::A),
            prev: std::mem::replace(&mut self.state, new_state),
            curr: new_state,
        }
    }
}

struct ButtonEventIterator {
    button: Option<Button>,
    prev: ButtonState,
    curr: ButtonState,
}

impl Iterator for ButtonEventIterator {
    type Item = ButtonEvent;

    fn next(&mut self) -> Option<Self::Item> {
        loop {
            let button = self.button?;
            let (next, prev, curr) = match button {
                Button::A => (Some(Button::B), self.prev.a, self.curr.a),
                Button::B => (Some(Button::X), self.prev.b, self.curr.b),
                Button::X => (Some(Button::Y), self.prev.x, self.curr.x),
                Button::Y => (Some(Button::Up), self.prev.y, self.curr.y),
                Button::Up => (Some(Button::Down), self.prev.up, self.curr.up),
                Button::Down => (Some(Button::Left), self.prev.down, self.curr.down),
                Button::Left => (Some(Button::Right), self.prev.left, self.curr.left),
                Button::Right => (Some(Button::L), self.prev.right, self.curr.right),
                Button::L => (Some(Button::R), self.prev.l, self.curr.l),
                Button::R => (Some(Button::Start), self.prev.r, self.curr.r),
                Button::Start => (Some(Button::Select), self.prev.start, self.curr.start),
                Button::Select => (Some(Button::Home), self.prev.select, self.curr.select),
                Button::Home => (Some(Button::VolUp), self.prev.home, self.curr.home),
                Button::VolUp => (Some(Button::VolDown), self.prev.vol_up, self.curr.vol_up),
                Button::VolDown => (Some(Button::Power), self.prev.vol_down, self.curr.vol_down),
                Button::Power => (None, self.prev.power, self.curr.power),
            };
            let event = match (prev, curr) {
                (false, true) => Some(ButtonEvent::Pressed(button)),
                (true, false) => Some(ButtonEvent::Released(button)),
                _ => None,
            };
            self.button = next;
            if event.is_some() {
                return event;
            }
        }
    }
}
