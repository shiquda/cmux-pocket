//! Implementation of `cmux-pocket qr` command.

use crate::cli::QrArgs;
use crate::endpoint::resolve_mobile_endpoint;
use crate::error::CliError;
use crate::output::{print_success, token_fingerprint};
use crate::uri::{build_import_uri, LOOPBACK_NOTICE, SECURITY_WARNING};
use cmux_pocket_macos::{ensure_token, load_config, GatewayConfig, PocketPaths};
use qrcode::render::unicode;
use qrcode::QrCode;
use serde::Serialize;

#[derive(Debug, Serialize)]
pub struct QrData {
    pub endpoint: String,
    pub endpoint_source: String,
    pub uri: String,
    pub qr_code: String,
    pub token_fingerprint: String,
    pub is_loopback: bool,
    pub tls: bool,
    pub name: String,
}

/// Renders a string as a Unicode ANSI QR code block.
pub fn generate_ansi_qr(content: &str, invert: bool) -> Result<String, CliError> {
    let code = QrCode::new(content.as_bytes()).map_err(|e| {
        CliError::RuntimeFailure(format!("Failed to encode content into QR code: {}", e))
    })?;

    let (dark_color, light_color) = if invert {
        (unicode::Dense1x2::Light, unicode::Dense1x2::Dark)
    } else {
        (unicode::Dense1x2::Dark, unicode::Dense1x2::Light)
    };

    let rendered = code
        .render::<unicode::Dense1x2>()
        .dark_color(dark_color)
        .light_color(light_color)
        .build();

    Ok(rendered)
}

/// Handles `cmux-pocket qr` command.
pub async fn handle_qr(
    paths: &PocketPaths,
    args: &QrArgs,
    json_mode: bool,
) -> Result<(), CliError> {
    let config = if paths.config_file.exists() {
        load_config(&paths.config_file)?
    } else {
        GatewayConfig::default()
    };

    let token_path = config.resolve_token_path(paths);
    let (token, _created) = ensure_token(&token_path)?;
    let fp = token_fingerprint(&token);

    let (endpoint, source) = resolve_mobile_endpoint(args.endpoint.as_deref(), &config).await?;

    let uri = build_import_uri(&endpoint, &token, args.name.as_deref());

    if args.url_only && !json_mode {
        println!("{}", uri);
        return Ok(());
    }

    let invert = !args.no_invert;
    let qr_block = generate_ansi_qr(&uri, invert)?;

    let name = args.name.clone().unwrap_or_else(|| {
        if endpoint.is_loopback {
            "USB / Local".to_string()
        } else {
            endpoint.host.clone()
        }
    });

    let data = QrData {
        endpoint: endpoint.raw.clone(),
        endpoint_source: source.to_string(),
        uri: uri.clone(),
        qr_code: qr_block.clone(),
        token_fingerprint: fp.clone(),
        is_loopback: endpoint.is_loopback,
        tls: endpoint.is_tls,
        name,
    };

    let mut prose = String::new();
    if !args.url_only {
        prose.push_str(&qr_block);
        prose.push_str("\n\n");
    }

    prose.push_str(&format!(
        "cmux-pocket Connection QR Code\n\
         ==============================\n\
         Endpoint:          {} (via {})\n\
         Token Fingerprint: {}\n\
         Import Link:       {}\n\n\
         Scan this QR code in the Android cmux-pocket app (Settings -> Scan QR),\n\
         or open the import link on an Android device with cmux-pocket installed.\n\n\
         Tip: Running in a light-themed terminal? Pass '--no-invert' to match your background.\n\n\
         {}\n",
        endpoint.raw, source, fp, uri, SECURITY_WARNING
    ));

    if endpoint.is_loopback {
        prose.push_str(&format!("\n{}\n", LOOPBACK_NOTICE));
    }

    print_success(&data, &prose, json_mode);
    Ok(())
}
