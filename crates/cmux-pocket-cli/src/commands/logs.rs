//! Implementation of `cmux-pocket logs` command.

use crate::cli::LogsArgs;
use crate::error::CliError;
use crate::output::print_success;
use cmux_pocket_macos::{load_config, GatewayConfig, PocketPaths};
use serde::Serialize;
use std::fs::File;
use std::io::{BufRead, BufReader, Seek, SeekFrom};
use std::path::Path;
use std::time::Duration;
use tokio::time::sleep;

#[derive(Debug, Serialize)]
pub struct LogsData {
    pub stdout_log: String,
    pub stderr_log: String,
    pub stdout_lines: Vec<String>,
    pub stderr_lines: Vec<String>,
}

fn read_last_n_lines(path: &Path, n: usize) -> Vec<String> {
    if !path.exists() {
        return Vec::new();
    }

    let file = match File::open(path) {
        Ok(f) => f,
        Err(_) => return Vec::new(),
    };

    let reader = BufReader::new(file);
    let all_lines: Vec<String> = reader.lines().map_while(Result::ok).collect();
    if all_lines.len() <= n {
        all_lines
    } else {
        all_lines[all_lines.len() - n..].to_vec()
    }
}

/// Handles `cmux-pocket logs` command.
pub async fn handle_logs(
    paths: &PocketPaths,
    args: &LogsArgs,
    json_mode: bool,
) -> Result<(), CliError> {
    let config = if paths.config_file.exists() {
        load_config(&paths.config_file).unwrap_or_default()
    } else {
        GatewayConfig::default()
    };
    let log_dir = config.resolve_log_dir(paths);
    let stdout_log = log_dir.join("gateway.stdout.log");
    let stderr_log = log_dir.join("gateway.stderr.log");

    if json_mode && !args.follow {
        let stdout_lines = read_last_n_lines(&stdout_log, args.lines);
        let stderr_lines = read_last_n_lines(&stderr_log, args.lines);

        let data = LogsData {
            stdout_log: stdout_log.display().to_string(),
            stderr_log: stderr_log.display().to_string(),
            stdout_lines,
            stderr_lines,
        };

        print_success(&data, "Log contents retrieved", true);
        return Ok(());
    }

    // Human output
    if !stdout_log.exists() && !stderr_log.exists() {
        if json_mode {
            let data = LogsData {
                stdout_log: stdout_log.display().to_string(),
                stderr_log: stderr_log.display().to_string(),
                stdout_lines: Vec::new(),
                stderr_lines: Vec::new(),
            };
            print_success(&data, "No log files found", true);
            return Ok(());
        } else {
            println!("No log files found in {}", log_dir.display());
            return Ok(());
        }
    }

    let stdout_lines = read_last_n_lines(&stdout_log, args.lines);
    let stderr_lines = read_last_n_lines(&stderr_log, args.lines);

    if !stdout_lines.is_empty() {
        println!("=== STDOUT ({}) ===", stdout_log.display());
        for line in stdout_lines {
            println!("{}", line);
        }
    }

    if !stderr_lines.is_empty() {
        println!("\n=== STDERR ({}) ===", stderr_log.display());
        for line in stderr_lines {
            eprintln!("{}", line);
        }
    }

    if args.follow {
        println!("\n--- Following log output (Ctrl+C to stop) ---");

        let mut stdout_pos = if stdout_log.exists() {
            File::open(&stdout_log)
                .map(|f| f.metadata().map(|m| m.len()).unwrap_or(0))
                .unwrap_or(0)
        } else {
            0
        };

        let mut stderr_pos = if stderr_log.exists() {
            File::open(&stderr_log)
                .map(|f| f.metadata().map(|m| m.len()).unwrap_or(0))
                .unwrap_or(0)
        } else {
            0
        };

        loop {
            if stdout_log.exists() {
                if let Ok(mut file) = File::open(&stdout_log) {
                    let len = file.metadata().map(|m| m.len()).unwrap_or(0);
                    if len > stdout_pos {
                        if file.seek(SeekFrom::Start(stdout_pos)).is_ok() {
                            let reader = BufReader::new(file);
                            for line in reader.lines().map_while(Result::ok) {
                                println!("[stdout] {}", line);
                            }
                        }
                        stdout_pos = len;
                    }
                }
            }

            if stderr_log.exists() {
                if let Ok(mut file) = File::open(&stderr_log) {
                    let len = file.metadata().map(|m| m.len()).unwrap_or(0);
                    if len > stderr_pos {
                        if file.seek(SeekFrom::Start(stderr_pos)).is_ok() {
                            let reader = BufReader::new(file);
                            for line in reader.lines().map_while(Result::ok) {
                                eprintln!("[stderr] {}", line);
                            }
                        }
                        stderr_pos = len;
                    }
                }
            }

            sleep(Duration::from_millis(500)).await;
        }
    }

    Ok(())
}
