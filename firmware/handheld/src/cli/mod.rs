//! Command line interface

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
        let line = line.trim_end();

        log::info!(":{}", line);
    }
}
