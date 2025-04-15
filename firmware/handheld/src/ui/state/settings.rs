use std::cell::{Cell, RefCell};
use std::rc::Rc;

use super::super::slint::Backend;
use crate::device::Device;
use crate::ui::state::{SettingDatetime, SettingEntry, SettingType, SettingValue};
use slint::{ComponentHandle, Model, ModelNotify, ModelRc, ModelTracker};
use time::OffsetDateTime;

use super::UiState;

mod settings {
    use crate::kvs::{keys, KvsKey};

    pub struct Page {
        pub entries: &'static [Entry],
    }

    pub enum Entry {
        Checkbox {
            name: &'static str,
            key: &'static KvsKey<bool>,
        },
        List {
            name: &'static str,
            key: &'static KvsKey<i32>,
            choices: &'static [&'static str],
        },
        SystemDatetime {
            name: &'static str,
        },
    }

    pub static PAGES: &[Page] = &[
        // Root page
        Page {
            entries: &[
                Entry::Checkbox {
                    name: "Dark Mode",
                    key: &keys::DARK_MODE,
                },
                Entry::SystemDatetime {
                    name: "Date and Time (UTC)",
                },
                Entry::List {
                    name: "Rumble Strength",
                    key: &keys::RUMBLE_LEVEL,
                    choices: &["Off", "Low", "Medium", "High"],
                },
                Entry::Checkbox {
                    name: "GB: Enable DMG mode",
                    key: &keys::GB_IS_DMG,
                },
                Entry::Checkbox {
                    name: "GB: Skip Boot Animation",
                    key: &keys::GB_SKIP_BOOT_ANIM,
                },
                Entry::List {
                    name: "GB: CGB Color Corrections",
                    key: &keys::CGB_COLOR_PROFILE,
                    choices: &["None", "GBC", "GBA", "GBA SP"],
                },
                Entry::Checkbox {
                    name: "GBA: Skip Boot Animation",
                    key: &keys::GBA_SKIP_BOOT_ANIM,
                },
                Entry::List {
                    name: "GBA: Color Corrections",
                    key: &keys::GBA_COLOR_PROFILE,
                    choices: &["None", "GBA", "GBA SP", "NDS", "NDS Lite", "NSO GBA"],
                },
                Entry::Checkbox {
                    name: "GBA: Enable Game Boy Player",
                    key: &keys::GBA_ENABLE_GBP,
                },
            ],
        },
    ];
}

impl UiState {
    /// Set up the "Settings" screen.
    pub(super) fn setup_settings(&mut self, state: &Rc<RefCell<UiState>>, _device: &mut Device) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();

        let state_ = state.clone();
        backend.on_setting_changed(move |i, value| {
            if let Some(model) = state_.borrow_mut().settings_model.as_ref() {
                model.changed(i as usize, value);
            }
        });
    }

    pub(super) fn on_settings_enter(&mut self) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();
        let model = Rc::new(SettingsModel::root(&mut Device::lock()));
        self.settings_model = Some(model.clone());
        backend.set_settings(ModelRc::from(model));
    }
}

pub struct SettingsModel {
    page: &'static settings::Page,
    notify: ModelNotify,
    datetime: Cell<OffsetDateTime>,
}

impl Model for SettingsModel {
    type Data = SettingEntry;

    fn row_count(&self) -> usize {
        self.page.entries.len()
    }

    fn row_data(&self, row: usize) -> Option<Self::Data> {
        let entry = self.page.entries.get(row)?;
        let row = match entry {
            settings::Entry::Checkbox { name, key } => SettingEntry {
                name: (*name).into(),
                r#type: SettingType::Checkbox,
                value: SettingValue {
                    bool_value: key.get().unwrap(),
                    ..SettingValue::default()
                },
                ..Default::default()
            },
            settings::Entry::List { name, key, choices } => SettingEntry {
                name: (*name).into(),
                r#type: SettingType::List,
                value: SettingValue {
                    int_value: key.get().unwrap(),
                    ..SettingValue::default()
                },
                choices: ModelRc::new(
                    choices
                        .iter()
                        .map(|x| slint::SharedString::from(*x))
                        .collect::<slint::VecModel<_>>(),
                ),
            },
            settings::Entry::SystemDatetime { name } => SettingEntry {
                name: (*name).into(),
                r#type: SettingType::Datetime,
                value: SettingValue {
                    datetime_value: {
                        let dt = self.datetime.get();
                        SettingDatetime {
                            year: dt.year(),
                            month: dt.month() as i32,
                            day: dt.day() as i32,
                            hour: dt.hour() as i32,
                            min: dt.minute() as i32,
                            sec: dt.second() as i32,
                        }
                    },
                    ..SettingValue::default()
                },
                ..Default::default()
            },
        };
        Some(row)
    }

    fn model_tracker(&self) -> &dyn ModelTracker {
        &self.notify
    }

    fn as_any(&self) -> &dyn core::any::Any {
        // a typical implementation just return `self`
        self
    }
}

impl SettingsModel {
    pub fn root(device: &mut Device) -> Self {
        Self::new(device, &settings::PAGES[0])
    }

    pub fn new(device: &mut Device, page: &'static settings::Page) -> Self {
        SettingsModel {
            page,
            notify: ModelNotify::default(),
            datetime: Cell::new(device.get_datetime()),
        }
    }

    pub fn changed(&self, index: usize, value: SettingValue) {
        let entry = match self.page.entries.get(index) {
            Some(x) => x,
            None => {
                log::info!("Unknown setting changed: {} -> {:?}", index, value);
                return;
            }
        };

        match entry {
            settings::Entry::Checkbox { key, .. } => key.set(&value.bool_value),
            settings::Entry::List { key, .. } => key.set(&value.int_value),
            settings::Entry::SystemDatetime { .. } => {
                let dt = convert_settings_datetime(&value.datetime_value).unwrap();
                let dt = dt.replace_second(0).unwrap();
                let dt = dt.assume_utc();
                Device::lock().set_datetime(dt);
                self.datetime.set(dt);
            }
        }
        self.notify.row_changed(index);
    }
}

/// Helper function used by the UI to be able to correctly modify individual datetime components.
pub fn settings_datetime_add(source: SettingDatetime, delta: SettingDatetime) -> SettingDatetime {
    fn inner(
        source: &SettingDatetime,
        delta: SettingDatetime,
    ) -> time::Result<time::PrimitiveDateTime> {
        let mut dt = convert_settings_datetime(source)?;
        dt = dt.replace_day(1)?; // The day will be re-added later.
        dt = dt.replace_year(((dt.year() as i32) + delta.year).min(2100).max(2000))?;
        if delta.month < 0 {
            dt = dt.replace_month(dt.month().nth_prev((-delta.month) as u8))?;
        } else {
            dt = dt.replace_month(dt.month().nth_next(delta.month as u8))?;
        }
        dt = dt.replace_hour(((dt.hour() as i32) + delta.hour).rem_euclid(24) as u8)?;
        dt = dt.replace_minute(((dt.minute() as i32) + delta.min).rem_euclid(60) as u8)?;
        dt = dt.replace_second(((dt.second() as i32) + delta.sec).rem_euclid(60) as u8)?;
        let day_max = time::util::days_in_month(dt.month(), dt.year()) as i32;
        if delta.day == 0 {
            // If we aren't changing the day, clamp it to the maximum days in the month.
            dt = dt.replace_day(source.day.min(day_max) as u8)?;
        } else {
            dt = dt.replace_day((source.day + delta.day - 1).rem_euclid(day_max) as u8 + 1)?;
        }
        Ok(dt)
    }
    match inner(&source, delta) {
        Ok(dt) => SettingDatetime {
            year: dt.year(),
            month: dt.month() as i32,
            day: dt.day() as i32,
            hour: dt.hour() as i32,
            min: dt.minute() as i32,
            sec: dt.second() as i32,
        },
        Err(_) => {
            log::warn!("Invalid date");
            source
        }
    }
}

fn convert_settings_datetime(source: &SettingDatetime) -> time::Result<time::PrimitiveDateTime> {
    let date = time::Date::from_calendar_date(
        source.year,
        (source.month as u8).try_into()?,
        source.day as u8,
    )?;
    let time = time::Time::from_hms(source.hour as u8, source.min as u8, source.sec as u8)?;
    Ok(time::PrimitiveDateTime::new(date, time))
}
