use cmux_pocket_macos::{
    generate_launchd_plist, parse_launchctl_print, LaunchdPlistConfig, PocketPaths,
    DEFAULT_LAUNCHD_LABEL,
};
use std::path::PathBuf;
use tempfile::TempDir;

#[test]
fn test_service_plist_generation_plan() {
    let temp = TempDir::new().unwrap();
    let paths = PocketPaths::from_home_dir(temp.path());

    let exe = PathBuf::from("/opt/homebrew/bin/cmux-pocket");
    let plist_config = LaunchdPlistConfig::new(&exe, &paths.config_file, &paths.log_dir);

    let plist_xml = generate_launchd_plist(&plist_config).unwrap();

    assert!(plist_xml.contains(&format!("<string>{}</string>", DEFAULT_LAUNCHD_LABEL)));
    assert!(plist_xml.contains("<string>/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket</string>"));
    assert!(plist_xml.contains("<string>gateway</string>"));
    assert!(plist_xml.contains("<string>run</string>"));
    assert!(plist_xml.contains("<string>--config</string>"));
    assert!(plist_xml.contains("<key>KeepAlive</key>\n\t<true/>") || plist_xml.contains("<true/>"));
}

#[test]
fn test_service_parse_launchctl_status() {
    let sample_output = r#"
gui/501/com.cmuxpocket.gateway = {
    active count = 1
    path = /Users/test/Library/LaunchAgents/com.cmuxpocket.gateway.plist
    state = running
    program = /opt/homebrew/bin/cmux-pocket
    pid = 4321
    last exit code = (never exited)
}
"#;

    let parsed = parse_launchctl_print(sample_output);
    assert!(parsed.registered);
    assert_eq!(parsed.state, "running");
    assert_eq!(parsed.pid, Some(4321));
}
