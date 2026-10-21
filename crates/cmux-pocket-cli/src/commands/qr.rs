//! Implementation of `cmux-pocket qr` command.

use crate::cli::QrArgs;
use crate::endpoint::{
    collect_candidates, resolve_default_index, EndpointCandidate, EndpointSource,
};
use crate::error::CliError;
use crate::output::{print_success, token_fingerprint};
use crate::picker::{self, PickOutcome};
use crate::uri::{build_import_uri, LOOPBACK_NOTICE, SECURITY_WARNING};
use cmux_pocket_macos::{ensure_token, load_config, GatewayConfig, PocketPaths};
use qrcode::render::unicode;
use qrcode::QrCode;
use serde::Serialize;

#[derive(Debug, Serialize)]
pub struct QrData {
    pub endpoint: String,
    pub endpoint_source: String,
    pub endpoint_verified: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub endpoint_note: Option<String>,
    pub uri: String,
    pub qr_code: String,
    pub token_fingerprint: String,
    pub is_loopback: bool,
    pub tls: bool,
    pub name: String,
    /// Every endpoint the picker offered, in selection order.
    pub candidates: Vec<EndpointCandidate>,
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

/// Profile name stored on the phone: explicit `--name`, otherwise the endpoint label.
fn profile_name(candidate: &EndpointCandidate, override_name: Option<&str>) -> String {
    override_name.map(str::to_string).unwrap_or_else(|| {
        if candidate.parsed.is_loopback {
            "USB / Local".to_string()
        } else {
            candidate.name.clone()
        }
    })
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

    let candidates =
        collect_candidates(args.endpoint.as_deref(), &config, Some(&paths.home_dir)).await?;

    let interactive = !json_mode
        && !args.url_only
        && !args.no_interactive
        && args.endpoint.is_none()
        && picker::is_interactive_terminal();

    // Interactive mode previews the rendered QR for every candidate, starting with the first.
    let (chosen_index, preview) = if interactive {
        let mut previews: Vec<(String, String, String)> = Vec::with_capacity(candidates.len());
        for candidate in &candidates {
            let name = profile_name(candidate, args.name.as_deref());
            let uri = build_import_uri(&candidate.parsed, &token, Some(&name));
            let qr = generate_ansi_qr(&uri, !args.no_invert)?;
            previews.push((uri, name, qr));
        }
        let blocks: Vec<String> = previews.iter().map(|preview| preview.2.clone()).collect();
        match picker::pick(&candidates, &blocks, &fp)? {
            PickOutcome::Selected(index) => (index, Some(previews.swap_remove(index))),
            PickOutcome::Cancelled => {
                println!("Cancelled.");
                return Ok(());
            }
        }
    } else {
        (resolve_default_index(&candidates)?, None)
    };

    let chosen = &candidates[chosen_index];
    let (uri, profile_name, qr_block) = match preview {
        Some(preview) => preview,
        None => {
            let name = profile_name(chosen, args.name.as_deref());
            let uri = build_import_uri(&chosen.parsed, &token, Some(&name));
            if args.url_only {
                println!("{}", uri);
                return Ok(());
            }
            let qr = generate_ansi_qr(&uri, !args.no_invert)?;
            (uri, name, qr)
        }
    };

    let data = QrData {
        endpoint: chosen.parsed.raw.clone(),
        endpoint_source: chosen.source_label.clone(),
        endpoint_verified: chosen.verified,
        endpoint_note: chosen.note.clone(),
        uri: uri.clone(),
        qr_code: qr_block.clone(),
        token_fingerprint: fp.clone(),
        is_loopback: chosen.parsed.is_loopback,
        tls: chosen.parsed.is_tls,
        name: profile_name.clone(),
        candidates: candidates.clone(),
    };

    let mut prose = String::new();
    if !args.url_only {
        prose.push_str(&qr_block);
        prose.push_str("\n\n");
    }

    let verification = if chosen.verified {
        "verified on this Mac"
    } else if chosen.source == EndpointSource::AdbReverse {
        "USB debugging only"
    } else {
        "not verified here"
    };
    prose.push_str(&format!(
        "cmux-pocket Connection QR Code\n\
         ==============================\n\
         Endpoint:          {} (via {}, {})\n\
         Profile Name:      {}\n\
         Token Fingerprint: {}\n\
         Import Link:       {}\n\n\
         Scan this QR code in the Android cmux-pocket app (Settings -> Scan QR),\n\
         or open the import link on an Android device with cmux-pocket installed.\n\n\
         Tip: Running in a light-themed terminal? Pass '--no-invert' to match your background.\n",
        chosen.parsed.raw, chosen.source_label, verification, profile_name, fp, uri
    ));

    if let Some(note) = &chosen.note {
        prose.push_str(&format!("Note: {}\n", note));
    }

    if !interactive && picker::is_interactive_terminal() {
        prose.push_str(
            "\nTip: Other saved endpoints are one keypress away — run `cmux-pocket qr` in a terminal to pick one.\n",
        );
    }

    if chosen.parsed.is_loopback {
        prose.push_str(&format!("\n{}\n", LOOPBACK_NOTICE));
    }

    prose.push_str(&format!("\n{}\n", SECURITY_WARNING));

    print_success(&data, &prose, json_mode);
    Ok(())
}
