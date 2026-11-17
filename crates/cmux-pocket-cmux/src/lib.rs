//! cmux backend abstraction, live CLI subprocess adapter, mock adapter, and events stream.

pub mod args;
pub mod backend;
pub mod error;
pub mod events;
pub mod lifecycle;
pub mod live;
pub mod mock;
pub mod tree_parser;

pub use args::*;
pub use backend::{CmuxBackend, CmuxCapabilities, TerminalInputKind};
pub use error::CmuxError;
pub use events::CmuxEventStream;
pub use lifecycle::{
    apply_agent_lifecycles, parse_agent_lifecycles, parse_agent_lifecycles_value,
    SurfaceAgentLifecycle,
};
pub use live::{LiveCmuxBackend, DEFAULT_COMMAND_TIMEOUT};
pub use mock::{MockCmuxBackend, MockTerminalSession};
pub use tree_parser::{extract_surface_id, parse_workspace_tree, parse_workspace_tree_value};
