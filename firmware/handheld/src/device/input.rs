use super::Device;
use crate::input::InputState;

impl Device<'_> {
    /// Get the current state of the internal buttons.
    pub fn get_input_state(&mut self, io_expander: [bool; 16]) -> Result<InputState, ()> {
        let mut state = InputState::default();
        state.btn_a = !io_expander[3];
        state.btn_b = !io_expander[4];
        state.btn_x = !io_expander[1];
        state.btn_y = !io_expander[2];
        state.btn_up = !io_expander[10];
        state.btn_down = !io_expander[13];
        state.btn_left = !io_expander[12];
        state.btn_right = !io_expander[11];
        state.btn_start = !io_expander[15];
        state.btn_select = !io_expander[14];
        state.btn_l1 = !io_expander[9];
        state.btn_r1 = !io_expander[0];
        state.btn_system = self.button_home.is_low();
        state.btn_vol_up = self.button_vol_up.is_low();
        state.btn_vol_down = self.button_vol_down.is_low();
        state.btn_power = self.button_power.is_low();
        Ok(state)
    }

    /// Get whether an HDMI cable is plugged in based on IO expander state
    #[cfg(feature = "rev1")]
    pub(super) fn parse_hdmi_detect(&mut self, io_expander: [bool; 16]) -> Result<bool, ()> {
        // Rev 1: HDMI hot plug detect is active-low.
        Ok(!io_expander[5])
    }

    #[cfg(feature = "rev1")]
    pub fn read_hdmi_detect(&mut self) -> Result<bool, ()> {
        let io_expander = self.io_expander.get_pins().map_err(|_| ())?;
        self.parse_hdmi_detect(io_expander)
    }
}
