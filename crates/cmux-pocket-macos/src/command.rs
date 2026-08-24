//! Safe and deterministic command construction primitives.
//!
//! Provides `CommandSpec` for building, inspecting, and executing platform commands
//! (e.g. `launchctl`, `cmux`) without brittle shell string interpolation.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::process::Command as StdCommand;

/// Description of a command invocation with deterministic argument and environment lists.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommandSpec {
    /// Executable program path or name.
    pub program: String,
    /// Ordered command line arguments.
    pub args: Vec<String>,
    /// Environment variables to set.
    pub env: BTreeMap<String, String>,
    /// Optional working directory.
    pub cwd: Option<PathBuf>,
}

impl CommandSpec {
    /// Creates a new `CommandSpec` with no arguments.
    pub fn new(program: impl Into<String>) -> Self {
        Self {
            program: program.into(),
            args: Vec::new(),
            env: BTreeMap::new(),
            cwd: None,
        }
    }

    /// Appends a single argument.
    pub fn arg(mut self, arg: impl Into<String>) -> Self {
        self.args.push(arg.into());
        self
    }

    /// Appends multiple arguments.
    pub fn args<I, S>(mut self, args: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        for arg in args {
            self.args.push(arg.into());
        }
        self
    }

    /// Sets an environment variable.
    pub fn env(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.env.insert(key.into(), value.into());
        self
    }

    /// Sets the working directory.
    pub fn cwd(mut self, dir: impl Into<PathBuf>) -> Self {
        self.cwd = Some(dir.into());
        self
    }

    /// Returns the full argv vector `[program, arg1, arg2, ...]`.
    pub fn to_argv(&self) -> Vec<String> {
        let mut argv = Vec::with_capacity(self.args.len() + 1);
        argv.push(self.program.clone());
        argv.extend(self.args.clone());
        argv
    }

    /// Renders a human-readable, safely quoted string representation of the command.
    pub fn display_command(&self) -> String {
        let mut parts = Vec::with_capacity(self.args.len() + 1);
        parts.push(quote_arg(&self.program));
        for arg in &self.args {
            parts.push(quote_arg(arg));
        }
        parts.join(" ")
    }

    /// Constructs a standard `std::process::Command` ready for execution.
    pub fn to_std_command(&self) -> StdCommand {
        let mut cmd = StdCommand::new(&self.program);
        cmd.args(&self.args);
        for (k, v) in &self.env {
            cmd.env(k, v);
        }
        if let Some(cwd) = &self.cwd {
            cmd.current_dir(cwd);
        }
        cmd
    }
}

fn quote_arg(arg: &str) -> String {
    if arg.is_empty() {
        return "''".to_string();
    }
    if arg
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.' | '/' | ':' | '='))
    {
        arg.to_string()
    } else {
        format!("'{}'", arg.replace('\'', "'\\''"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_command_spec_builder() {
        let spec = CommandSpec::new("launchctl")
            .arg("bootstrap")
            .arg("gui/501")
            .arg("/path/to/plist");

        assert_eq!(
            spec.to_argv(),
            vec!["launchctl", "bootstrap", "gui/501", "/path/to/plist"]
        );
        assert_eq!(
            spec.display_command(),
            "launchctl bootstrap gui/501 /path/to/plist"
        );
    }

    #[test]
    fn test_quote_args_with_spaces() {
        let spec = CommandSpec::new("cmux")
            .arg("workspace")
            .arg("create")
            .arg("my project workspace");

        assert_eq!(
            spec.display_command(),
            "cmux workspace create 'my project workspace'"
        );
    }
}
