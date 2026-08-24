//! Loopback validation primitives.
//!
//! Enforces that Gateway listeners and clients only bind to or connect to loopback
//! addresses (`127.0.0.1`, `::1`, `localhost`, `127.0.0.0/8`). Public binds, LAN binds,
//! and wildcard binds (`0.0.0.0`, `::`) are strictly forbidden.

use crate::error::LoopbackError;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

/// Returns true if the given IP address is a loopback address.
///
/// Handles IPv4 (`127.0.0.0/8`), IPv6 (`::1`), and IPv4-mapped IPv6 loopback addresses.
pub fn is_loopback_ip(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback(),
        IpAddr::V6(v6) => {
            if v6.is_loopback() {
                return true;
            }
            // Check for IPv4-mapped IPv6 address (::ffff:127.x.x.x)
            if let Some(v4) = v6.to_ipv4_mapped() {
                return v4.is_loopback();
            }
            // Check segment-based IPv4-mapped fallback
            let segs = v6.segments();
            if segs[0] == 0
                && segs[1] == 0
                && segs[2] == 0
                && segs[3] == 0
                && segs[4] == 0
                && segs[5] == 0xffff
            {
                let v4 = Ipv4Addr::new(
                    (segs[6] >> 8) as u8,
                    (segs[6] & 0xff) as u8,
                    (segs[7] >> 8) as u8,
                    (segs[7] & 0xff) as u8,
                );
                return v4.is_loopback();
            }
            false
        }
    }
}

/// Normalizes and returns true if the given hostname or IP string represents a loopback host.
///
/// Accepts:
/// - `"localhost"` (case-insensitive)
/// - `"127.0.0.1"`, `"127.0.0.2"`, `"127.x.y.z"`
/// - `"::1"`, `"[::1]"`
/// - `"::ffff:127.0.0.1"`
///
/// Denies:
/// - `"0.0.0.0"`, `"::"`
/// - Public / LAN IPs (`192.168.1.1`, `10.0.0.1`, etc.)
/// - External hostnames (`"example.com"`, `"cmux.local"`, etc.)
pub fn is_loopback_host(host: &str) -> bool {
    let clean = host.trim().trim_start_matches('[').trim_end_matches(']');
    if clean.eq_ignore_ascii_case("localhost") {
        return true;
    }
    if let Ok(ip) = clean.parse::<IpAddr>() {
        return is_loopback_ip(&ip);
    }
    false
}

/// Validates that a host string is a valid loopback host.
///
/// Returns `Ok(())` if loopback, or a specific `LoopbackError` if invalid or non-loopback.
pub fn validate_loopback_host(host: &str) -> Result<(), LoopbackError> {
    let clean = host.trim().trim_start_matches('[').trim_end_matches(']');
    if clean.is_empty() {
        return Err(LoopbackError::InvalidAddress(
            "Host cannot be empty".to_string(),
        ));
    }

    if clean == "0.0.0.0" || clean == "::" {
        return Err(LoopbackError::WildcardBindForbidden(host.to_string()));
    }

    if clean.eq_ignore_ascii_case("localhost") {
        return Ok(());
    }

    match clean.parse::<IpAddr>() {
        Ok(ip) => {
            if is_loopback_ip(&ip) {
                Ok(())
            } else {
                Err(LoopbackError::NonLoopbackIp(ip))
            }
        }
        Err(_) => Err(LoopbackError::NonLoopbackHost(host.to_string())),
    }
}

/// Validates that a socket address has a loopback IP.
pub fn validate_loopback_addr(addr: &SocketAddr) -> Result<(), LoopbackError> {
    if is_loopback_ip(&addr.ip()) {
        Ok(())
    } else if addr.ip().is_unspecified() {
        Err(LoopbackError::WildcardBindForbidden(addr.to_string()))
    } else {
        Err(LoopbackError::NonLoopbackIp(addr.ip()))
    }
}

/// Parses a host string and port into a validated loopback `SocketAddr`.
///
/// If `host` is `"localhost"`, it defaults to IPv4 loopback `127.0.0.1:<port>`.
pub fn parse_and_validate_bind(host: &str, port: u16) -> Result<SocketAddr, LoopbackError> {
    validate_loopback_host(host)?;

    let clean = host.trim().trim_start_matches('[').trim_end_matches(']');
    if clean.eq_ignore_ascii_case("localhost") {
        return Ok(SocketAddr::new(
            IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)),
            port,
        ));
    }

    let ip = clean
        .parse::<IpAddr>()
        .map_err(|_| LoopbackError::InvalidAddress(format!("Cannot parse IP: {host}")))?;

    let addr = SocketAddr::new(ip, port);
    validate_loopback_addr(&addr)?;
    Ok(addr)
}

/// Validates that a URL string (e.g. `ws://127.0.0.1:8088` or `http://localhost:8088`) targets loopback.
pub fn validate_loopback_url(url_str: &str) -> Result<(), LoopbackError> {
    let host_part = if let Some(idx) = url_str.find("://") {
        &url_str[idx + 3..]
    } else {
        url_str
    };

    // Strip trailing path/query/fragment
    let host_part = host_part.split('/').next().unwrap_or(host_part);
    let host_part = host_part.split('?').next().unwrap_or(host_part);
    let host_part = host_part.split('#').next().unwrap_or(host_part);

    // Extract host without port
    let host = if host_part.starts_with('[') {
        if let Some(close_idx) = host_part.find(']') {
            &host_part[1..close_idx]
        } else {
            return Err(LoopbackError::InvalidAddress(url_str.to_string()));
        }
    } else if let Some(colon_idx) = host_part.find(':') {
        &host_part[..colon_idx]
    } else {
        host_part
    };

    validate_loopback_host(host).map_err(|_| LoopbackError::NonLoopbackUrl(url_str.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv6Addr;

    #[test]
    fn test_loopback_ipv4_allow() {
        assert!(is_loopback_host("127.0.0.1"));
        assert!(is_loopback_host("127.0.0.2"));
        assert!(is_loopback_host("127.1.2.3"));
        assert!(is_loopback_host("127.255.255.254"));
        assert!(validate_loopback_host("127.0.0.1").is_ok());
        assert!(validate_loopback_host("127.0.0.2").is_ok());
    }

    #[test]
    fn test_loopback_ipv6_allow() {
        assert!(is_loopback_host("::1"));
        assert!(is_loopback_host("[::1]"));
        assert!(validate_loopback_host("::1").is_ok());
        assert!(validate_loopback_host("[::1]").is_ok());
    }

    #[test]
    fn test_loopback_localhost_allow() {
        assert!(is_loopback_host("localhost"));
        assert!(is_loopback_host("LOCALHOST"));
        assert!(is_loopback_host("LocalHost"));
        assert!(validate_loopback_host("localhost").is_ok());
    }

    #[test]
    fn test_wildcard_bind_forbidden() {
        assert!(!is_loopback_host("0.0.0.0"));
        assert!(!is_loopback_host("::"));
        match validate_loopback_host("0.0.0.0") {
            Err(LoopbackError::WildcardBindForbidden(_)) => {}
            other => panic!("Expected WildcardBindForbidden, got: {other:?}"),
        }
        match validate_loopback_host("::") {
            Err(LoopbackError::WildcardBindForbidden(_)) => {}
            other => panic!("Expected WildcardBindForbidden, got: {other:?}"),
        }
    }

    #[test]
    fn test_non_loopback_ip_denied() {
        assert!(!is_loopback_host("192.168.1.1"));
        assert!(!is_loopback_host("10.0.0.1"));
        assert!(!is_loopback_host("172.16.0.1"));
        assert!(!is_loopback_host("8.8.8.8"));
        assert!(!is_loopback_host("1.1.1.1"));

        match validate_loopback_host("192.168.1.100") {
            Err(LoopbackError::NonLoopbackIp(ip)) => {
                assert_eq!(ip.to_string(), "192.168.1.100");
            }
            other => panic!("Expected NonLoopbackIp, got: {other:?}"),
        }
    }

    #[test]
    fn test_non_loopback_hostname_denied() {
        assert!(!is_loopback_host("example.com"));
        assert!(!is_loopback_host("cmux.local"));
        assert!(!is_loopback_host("evil.com"));

        match validate_loopback_host("example.com") {
            Err(LoopbackError::NonLoopbackHost(h)) => {
                assert_eq!(h, "example.com");
            }
            other => panic!("Expected NonLoopbackHost, got: {other:?}"),
        }
    }

    #[test]
    fn test_parse_and_validate_bind() {
        let addr1 = parse_and_validate_bind("127.0.0.1", 8088).unwrap();
        assert_eq!(
            addr1,
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 8088)
        );

        let addr2 = parse_and_validate_bind("localhost", 8089).unwrap();
        assert_eq!(
            addr2,
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 8089)
        );

        let addr3 = parse_and_validate_bind("::1", 8088).unwrap();
        assert_eq!(
            addr3,
            SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 8088)
        );

        assert!(parse_and_validate_bind("0.0.0.0", 8088).is_err());
        assert!(parse_and_validate_bind("192.168.1.1", 8088).is_err());
    }

    #[test]
    fn test_validate_loopback_url() {
        assert!(validate_loopback_url("ws://127.0.0.1:8088/ws").is_ok());
        assert!(validate_loopback_url("http://localhost:8088/status").is_ok());
        assert!(validate_loopback_url("ws://[::1]:8088/ws").is_ok());
        assert!(validate_loopback_url("ws://192.168.1.10:8088/ws").is_err());
        assert!(validate_loopback_url("ws://example.com:8088/ws").is_err());
    }
}
