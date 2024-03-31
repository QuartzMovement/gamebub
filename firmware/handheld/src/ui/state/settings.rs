use std::cell::Cell;

use crate::device::{kvs, Device};
use crate::ui::state::{SettingDatetime, SettingEntry, SettingType, SettingValue};
use slint::{Model, ModelNotify, ModelTracker};
use time::OffsetDateTime;

pub struct SettingsModel {
    notify: ModelNotify,
    datetime: Cell<OffsetDateTime>,
}

impl Model for SettingsModel {
    type Data = SettingEntry;

    fn row_count(&self) -> usize {
        2
    }

    fn row_data(&self, row: usize) -> Option<Self::Data> {
        match row {
            0 => Some(SettingEntry {
                name: "Dark mode".into(),
                r#type: SettingType::Checkbox,
                value: SettingValue {
                    bool_value: kvs::keys::DARK_MODE.get().unwrap(),
                    ..SettingValue::default()
                },
            }),
            1 => Some(SettingEntry {
                name: "Date and Time".into(),
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
            }),
            _ => None,
        }
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
    pub fn new(device: &mut Device) -> Self {
        SettingsModel {
            notify: ModelNotify::default(),
            datetime: Cell::new(device.get_datetime()),
        }
    }

    pub fn changed(&self, index: usize) {
        match index {
            1 => self.datetime.set(Device::lock().get_datetime()),
            _ => {}
        }
        self.notify.row_changed(index);
    }
}
