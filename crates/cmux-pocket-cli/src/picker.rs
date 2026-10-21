//! Terminal picker for choosing a phone endpoint.
//!
//! Interactive mode is intentionally dependency-free: it reads arrow keys and j/k through a
//! short raw-mode loop on stdin, draws into the alternate screen, and restores the terminal on
//! every exit path (including panic). Non-TTY callers never reach this module.

use crate::endpoint::EndpointCandidate;
use std::io::{self, IsTerminal, Write};

/// Returns true when the process owns an interactive terminal.
pub fn is_interactive_terminal() -> bool {
    io::stdin().is_terminal() && io::stdout().is_terminal()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PickerState {
    selected: usize,
    len: usize,
}

impl PickerState {
    pub fn new(len: usize) -> Self {
        Self { selected: 0, len }
    }

    pub fn selected(&self) -> usize {
        self.selected
    }

    pub fn up(&mut self) {
        self.selected = self.selected.saturating_sub(1);
    }

    pub fn down(&mut self) {
        if self.len > 0 {
            self.selected = (self.selected + 1).min(self.len - 1);
        }
    }
}

/// Result of an interactive pick.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PickOutcome {
    Selected(usize),
    Cancelled,
}

/// Shows the endpoint picker with a live QR preview and returns the chosen index.
///
/// `qr_blocks[i]` must be the rendered QR for `candidates[i]`; the first one is shown before
/// any keypress so entering interactive mode immediately displays a scannable code.
pub fn pick(
    candidates: &[EndpointCandidate],
    qr_blocks: &[String],
    token_fingerprint: &str,
) -> io::Result<PickOutcome> {
    if candidates.is_empty() {
        return Ok(PickOutcome::Cancelled);
    }
    imp::pick(candidates, qr_blocks, token_fingerprint)
}

fn render(
    out: &mut impl Write,
    candidates: &[EndpointCandidate],
    qr_blocks: &[String],
    selected: usize,
    token_fingerprint: &str,
) -> io::Result<()> {
    write!(out, "\x1b[2J\x1b[H")?;
    let candidate = &candidates[selected];
    writeln!(
        out,
        "cmux-pocket QR — {} ({}/{})",
        candidate.name,
        selected + 1,
        candidates.len()
    )?;
    writeln!(out, "\x1b[7m{}\x1b[0m", candidate.url)?;
    let badge = if candidate.verified {
        "verified"
    } else {
        "unverified"
    };
    writeln!(out, "[{badge}] {}", candidate.source_label)?;
    if let Some(note) = &candidate.note {
        writeln!(out, "Note: {note}")?;
    }
    writeln!(out)?;
    if let Some(qr) = qr_blocks.get(selected) {
        writeln!(out, "{qr}")?;
    }
    writeln!(out)?;
    writeln!(out, "Token: {token_fingerprint}")?;
    write!(
        out,
        "↑/↓ or j/k switch endpoint · Enter confirm · q/Esc cancel"
    )?;
    out.flush()
}

#[cfg(unix)]
mod imp {
    use super::*;

    struct RawTerminal {
        original: libc::termios,
    }

    impl RawTerminal {
        fn enter() -> io::Result<Self> {
            let mut original: libc::termios = unsafe { std::mem::zeroed() };
            if unsafe { libc::tcgetattr(libc::STDIN_FILENO, &mut original) } != 0 {
                return Err(io::Error::last_os_error());
            }
            let mut raw = original;
            raw.c_lflag &= !(libc::ICANON | libc::ECHO | libc::ISIG | libc::IEXTEN);
            raw.c_iflag &= !(libc::IXON | libc::ICRNL | libc::INLCR);
            raw.c_cc[libc::VMIN] = 1;
            raw.c_cc[libc::VTIME] = 0;
            if unsafe { libc::tcsetattr(libc::STDIN_FILENO, libc::TCSAFLUSH, &raw) } != 0 {
                return Err(io::Error::last_os_error());
            }
            let mut out = io::stdout();
            write!(out, "\x1b[?1049h\x1b[?25l")?;
            out.flush()?;
            Ok(Self { original })
        }
    }

    impl Drop for RawTerminal {
        fn drop(&mut self) {
            unsafe {
                libc::tcsetattr(libc::STDIN_FILENO, libc::TCSAFLUSH, &self.original);
            }
            let mut out = io::stdout();
            let _ = write!(out, "\x1b[?25h\x1b[?1049l");
            let _ = out.flush();
        }
    }

    fn read_byte() -> io::Result<u8> {
        // Read the fd directly: `io::stdin()` buffers, which would hide queued arrow-key
        // bytes from `poll` and turn every escape sequence into a bare Esc.
        let mut byte = [0u8; 1];
        loop {
            let read = unsafe { libc::read(libc::STDIN_FILENO, byte.as_mut_ptr().cast(), 1) };
            if read == 1 {
                return Ok(byte[0]);
            }
            if read < 0 {
                let error = io::Error::last_os_error();
                if error.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(error);
            }
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "terminal input closed",
            ));
        }
    }

    /// Returns true when another byte is immediately available (used to disambiguate Esc).
    fn byte_ready() -> bool {
        let mut descriptor = libc::pollfd {
            fd: libc::STDIN_FILENO,
            events: libc::POLLIN,
            revents: 0,
        };
        unsafe { libc::poll(&mut descriptor, 1, 30) > 0 }
    }

    fn read_direction(first: u8) -> io::Result<Option<Direction>> {
        if first != b'[' && first != b'O' {
            return Ok(None);
        }
        Ok(match read_byte()? {
            b'A' => Some(Direction::Up),
            b'B' => Some(Direction::Down),
            _ => None,
        })
    }

    enum Direction {
        Up,
        Down,
    }

    pub fn pick(
        candidates: &[EndpointCandidate],
        qr_blocks: &[String],
        token_fingerprint: &str,
    ) -> io::Result<PickOutcome> {
        let _terminal = RawTerminal::enter()?;
        let mut state = PickerState::new(candidates.len());
        let mut out = io::stdout();
        loop {
            render(
                &mut out,
                candidates,
                qr_blocks,
                state.selected(),
                token_fingerprint,
            )?;
            match read_byte()? {
                b'\r' | b'\n' => return Ok(PickOutcome::Selected(state.selected())),
                b'q' | b'Q' | 0x03 | 0x04 => return Ok(PickOutcome::Cancelled),
                b'k' => state.up(),
                b'j' => state.down(),
                0x1b => {
                    if !byte_ready() {
                        return Ok(PickOutcome::Cancelled);
                    }
                    match read_direction(read_byte()?)? {
                        Some(Direction::Up) => state.up(),
                        Some(Direction::Down) => state.down(),
                        None => {}
                    }
                }
                _ => {}
            }
        }
    }
}

#[cfg(not(unix))]
mod imp {
    use super::*;

    pub fn pick(
        _candidates: &[EndpointCandidate],
        _qr_blocks: &[String],
        _token_fingerprint: &str,
    ) -> io::Result<PickOutcome> {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "Interactive endpoint selection requires a Unix terminal",
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn navigation_clamps_at_both_ends() {
        let mut state = PickerState::new(3);
        state.up();
        assert_eq!(state.selected(), 0);
        state.down();
        state.down();
        state.down();
        assert_eq!(state.selected(), 2);
        state.up();
        assert_eq!(state.selected(), 1);
    }

    fn candidate(name: &str, url: &str) -> crate::endpoint::EndpointCandidate {
        crate::endpoint::EndpointCandidate {
            url: url.to_string(),
            name: name.to_string(),
            source: crate::endpoint::EndpointSource::TailscaleServe,
            source_label: "Tailscale Serve route".to_string(),
            verified: true,
            can_default: true,
            note: None,
            parsed: cmux_pocket_macos::parse_and_validate_endpoint(url).unwrap(),
        }
    }

    #[test]
    fn empty_picker_is_cancelled() {
        let outcome = pick(&[], &[], "sha256:test").unwrap();
        assert_eq!(outcome, PickOutcome::Cancelled);
    }

    #[test]
    fn render_shows_the_selected_endpoint_qr_immediately() {
        let candidates = [
            candidate("Tailscale", "wss://mac.example.ts.net"),
            candidate("LAN", "wss://lan.example"),
        ];
        let blocks = ["QR-FIRST".to_string(), "QR-SECOND".to_string()];
        let mut output = Vec::new();
        render(&mut output, &candidates, &blocks, 0, "sha256:abc").unwrap();
        let text = String::from_utf8_lossy(&output).to_string();
        assert!(text.contains("Tailscale (1/2)"));
        assert!(text.contains("[verified]"));
        assert!(text.contains("QR-FIRST"));
        assert!(!text.contains("QR-SECOND"));
        assert!(text.contains("Enter confirm"));

        output.clear();
        render(&mut output, &candidates, &blocks, 1, "sha256:abc").unwrap();
        let text = String::from_utf8_lossy(&output).to_string();
        assert!(text.contains("LAN (2/2)"));
        assert!(text.contains("QR-SECOND"));
        assert!(!text.contains("QR-FIRST"));
    }
}
