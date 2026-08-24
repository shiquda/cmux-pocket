//! cmux backend abstraction, live CLI subprocess adapter, mock adapter, and events stream.

pub mod args;
pub mod backend;
pub mod error;
pub mod events;
pub mod live;
pub mod mock;
pub mod tree_parser;

pub use args::*;
pub use backend::CmuxBackend;
pub use error::CmuxError;
pub use events::CmuxEventStream;
pub use live::{LiveCmuxBackend, DEFAULT_COMMAND_TIMEOUT};
pub use mock::{MockCmuxBackend, MockTerminalSession};
pub use tree_parser::{extract_surface_id, parse_workspace_tree, parse_workspace_tree_value};
