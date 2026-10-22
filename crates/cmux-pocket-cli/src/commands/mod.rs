//! CLI subcommand handlers.

pub mod config;
pub mod doctor;
pub mod endpoint;
pub mod gateway;
pub mod logs;
pub mod qr;
pub mod service;
pub mod setup;
pub mod status;
pub mod token;
pub mod upgrade;

pub use config::handle_config;
pub use doctor::handle_doctor;
pub use endpoint::handle_endpoint;
pub use gateway::handle_gateway;
pub use logs::handle_logs;
pub use qr::handle_qr;
pub use service::handle_service;
pub use setup::handle_setup;
pub use status::handle_status;
pub use token::handle_token;
pub use upgrade::handle_upgrade;
