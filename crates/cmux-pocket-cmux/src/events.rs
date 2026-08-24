//! Typed event line stream abstraction for `cmux events`.
//!
//! Handles line-delimited JSON parsing, malformed lines, EOF, and process lifecycle
//! cleanup on drop.

use serde_json::Value;
use std::path::Path;
use std::process::Stdio;
use tokio::io::{AsyncBufReadExt, BufReader, Lines};
use tokio::process::{Child, ChildStdout, Command};
use tokio::sync::mpsc;
use tracing::{debug, warn};

use crate::args::events_args;
use crate::error::CmuxError;

/// A stream of events from `cmux events` or a mock event source.
pub struct CmuxEventStream {
    inner: EventStreamInner,
}
#[allow(clippy::large_enum_variant)]
enum EventStreamInner {
    Live {
        child: Child,
        lines: Lines<BufReader<ChildStdout>>,
    },
    Mock {
        receiver: mpsc::UnboundedReceiver<Value>,
    },
}

impl CmuxEventStream {
    /// Spawns a live `cmux events` child process and begins reading from stdout.
    pub fn spawn(cmux_path: &Path) -> Result<Self, CmuxError> {
        let args = events_args();
        let mut cmd = Command::new(cmux_path);
        cmd.args(&args).stdout(Stdio::piped()).stderr(Stdio::null());

        let mut child = cmd.spawn().map_err(|e| {
            if e.kind() == std::io::ErrorKind::NotFound {
                CmuxError::unavailable(format!("cmux binary not found at {}", cmux_path.display()))
            } else {
                CmuxError::unavailable(format!("failed to spawn cmux events: {e}"))
            }
        })?;

        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| CmuxError::unavailable("failed to capture cmux events stdout pipe"))?;

        let lines = BufReader::new(stdout).lines();

        Ok(Self {
            inner: EventStreamInner::Live { child, lines },
        })
    }

    /// Creates a mock event stream backed by an unbounded mpsc channel receiver.
    pub fn mock(receiver: mpsc::UnboundedReceiver<Value>) -> Self {
        Self {
            inner: EventStreamInner::Mock { receiver },
        }
    }

    /// Creates a mock event stream from a static list of `Value` items.
    pub fn from_values(values: Vec<Value>) -> Self {
        let (tx, rx) = mpsc::unbounded_channel();
        for val in values {
            let _ = tx.send(val);
        }
        Self::mock(rx)
    }

    /// Yields the next parsed JSON event from the stream.
    ///
    /// Malformed lines are skipped with a warning log and do not terminate the stream.
    /// Returns `Ok(None)` on EOF or when the mock channel closes.
    pub async fn next_event(&mut self) -> Result<Option<Value>, CmuxError> {
        match &mut self.inner {
            EventStreamInner::Live { lines, .. } => loop {
                match lines.next_line().await {
                    Ok(Some(raw_line)) => {
                        let trimmed = raw_line.trim();
                        if trimmed.is_empty() {
                            continue;
                        }
                        match serde_json::from_str::<Value>(trimmed) {
                            Ok(val) => return Ok(Some(val)),
                            Err(e) => {
                                warn!("cmux events: skipping malformed JSON line: {e}");
                                debug!("cmux events malformed line was: {trimmed:?}");
                                continue;
                            }
                        }
                    }
                    Ok(None) => return Ok(None),
                    Err(e) => return Err(CmuxError::Io(e)),
                }
            },
            EventStreamInner::Mock { receiver } => Ok(receiver.recv().await),
        }
    }
}

impl Drop for CmuxEventStream {
    fn drop(&mut self) {
        if let EventStreamInner::Live { child, .. } = &mut self.inner {
            debug!("Terminating live cmux events child process");
            let _ = child.start_kill();
        }
    }
}
