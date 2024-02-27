use super::Device;
use crate::ui::buttons::ButtonState;

impl Device<'_> {
    /// Get the current state of the buttons.
    pub fn read_button_state(&mut self) -> Result<ButtonState, ()> {
        let io_expander = self.io_expander.get_pins().map_err(|_| ())?;
        Ok(ButtonState {
            a: !io_expander[3],
            b: !io_expander[4],
            x: !io_expander[1],
            y: !io_expander[2],
            up: !io_expander[10],
            down: !io_expander[13],
            left: !io_expander[12],
            right: !io_expander[11],
            start: !io_expander[15],
            select: !io_expander[14],
            l: !io_expander[9],
            r: !io_expander[0],
            home: self.button_home.is_low(),
            vol_up: self.button_vol_up.is_low(),
            vol_down: self.button_vol_down.is_low(),
            power: self.button_power.is_low(),
        })
    }
}
