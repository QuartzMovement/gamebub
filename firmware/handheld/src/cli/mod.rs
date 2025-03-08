//! Command line interface

use crate::kvs;

/// Start the CLI thread. Called once during system init.
pub fn start() {
    std::thread::Builder::new()
        .name("CLI".to_string())
        .stack_size(16 * 1024)
        .spawn(cli_thread)
        .unwrap();
}

fn cli_thread() -> ! {
    let mut line = String::new();
    let stdin = std::io::stdin();
    loop {
        line.clear();
        if let Err(e) = stdin.read_line(&mut line) {
            log::error!("Error reading line: {:?}", e);
        }
        let line = match line.strip_prefix('>') {
            Some(x) => x,
            None => continue,
        };
        let mut line = line.trim_end().split(',');

        let command = line.next().unwrap();
        let args = line;

        let result = match command {
            "get_hwinfo" => handle_get_hwinfo(args),
            _ => Err("unknown command".to_string()),
        };
        if let Err(error) = result {
            println!("<error,{error}");
        }
    }
}

/// `>get_hwinfo`: returns `<ok,{serial number},{hardware revision}`
fn handle_get_hwinfo<'a>(_args: impl Iterator<Item = &'a str>) -> Result<(), String> {
    println!(
        "<ok,{},{}",
        kvs::keys::DEVICE_SERIAL.get().as_deref().unwrap_or(""),
        kvs::keys::DEVICE_REVISION.get().unwrap_or_default(),
    );
    Ok(())
}
