use std::{cell::RefCell, ops::DerefMut, rc::Rc};

use super::super::slint::Backend;
use slint::ComponentHandle;

use crate::{
    bitstream::{self, Bitstream, CurrentBitstream},
    device::Device,
};

use super::UiState;

impl UiState {
    /// Set up the "Game" screen.
    pub(super) fn setup_game(&mut self, _state: &Rc<RefCell<UiState>>, _device: &mut Device) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();

        backend.on_game_set_paused(move |paused| {
            match bitstream::current().deref_mut() {
                CurrentBitstream::None => {}
                CurrentBitstream::Gameboy(x) => {
                    x.set_paused(paused).unwrap();
                    if paused {
                        // TODO handle error more gracefully
                        x.persist_ram().unwrap();
                    }
                }
                CurrentBitstream::Gba(x) => {
                    x.set_paused(paused).unwrap();
                    if paused {
                        x.persist_save().unwrap();
                    }
                }
            }
        });

        backend.on_game_reset(move || match bitstream::current().deref_mut() {
            CurrentBitstream::None => {}
            CurrentBitstream::Gameboy(x) => x.reset().unwrap(),
            CurrentBitstream::Gba(x) => x.reset().unwrap(),
        });
    }
}
