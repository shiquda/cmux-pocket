//! CLI subcommand handlers.

pub mod config;
pub mod doctor;
pub mod gateway;
pub mod logs;
pub mod service;
pub mod setup;
pub mod status;
pub mod token;

pub use config::handle_config;
pub use doctor::handle_doctor;
pub use gateway::handle_gateway;
pub use logs::handle_logs;
pub use service::handle_service;
pub use setup::handle_setup;
pub use status::handle_status;
pub use token::handle_token;
