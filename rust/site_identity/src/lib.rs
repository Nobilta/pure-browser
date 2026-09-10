//! Filtering site identity, never an origin permission key. PSL includes PRIVATE tenants.
use std::borrow::Cow;
use std::net::IpAddr;

pub fn canonical_host(host: &str) -> Option<Cow<'_, str>> {
    let host = host.trim_end_matches('.');
    if host.is_empty() || host.len() > 253 {
        return None;
    }
    let ip = host
        .strip_prefix('[')
        .and_then(|h| h.strip_suffix(']'))
        .unwrap_or(host);
    if let Ok(address) = ip.parse::<IpAddr>() {
        return Some(Cow::Owned(address.to_string()));
    }
    if host.is_ascii()
        && !host.bytes().any(|b| b.is_ascii_uppercase())
        && host.split('.').all(|l| {
            !l.is_empty()
                && l.len() <= 63
                && !l.starts_with('-')
                && !l.ends_with('-')
                && l.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
        })
    {
        return Some(Cow::Borrowed(host));
    }
    idna::domain_to_ascii_strict(host).ok().map(Cow::Owned)
}

/// None for IPs, single-label hosts or a public suffix itself.
pub fn registrable_domain(host: &str) -> Option<String> {
    let canonical = canonical_host(host)?;
    if canonical.parse::<IpAddr>().is_ok() {
        return None;
    }
    psl::domain_str(&canonical).map(str::to_owned)
}

pub fn same_site(a: &str, b: &str) -> bool {
    let (Some(a), Some(b)) = (canonical_host(a), canonical_host(b)) else {
        return false;
    };
    if a == b {
        return true;
    }
    if a.parse::<IpAddr>().is_ok() || b.parse::<IpAddr>().is_ok() {
        return false;
    }
    match (psl::domain_str(&a), psl::domain_str(&b)) {
        (Some(a), Some(b)) => a == b,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn site_boundaries() {
        for (a, b, expected) in [
            ("www.example.com", "cdn.example.com", true),
            ("www.example.co.uk", "cdn.example.co.uk", true),
            ("example.com", "other.com", false),
            ("alice.github.io", "bob.github.io", false),
            ("cdn.alice.github.io", "alice.github.io", true),
            ("a.b.ck", "x.b.ck", false),  // *.ck
            ("www.ck", "a.www.ck", true), // !www.ck
            ("a.city.kawasaki.jp", "b.city.kawasaki.jp", true),
            ("a.kawasaki.jp", "b.kawasaki.jp", false),
            ("127.0.0.1", "127.0.0.2", false),
            ("127.0.0.1", "127.0.0.1", true),
            ("[::1]", "[0:0:0:0:0:0:0:1]", true),
            ("localhost", "sub.localhost", false),
            ("localhost", "localhost", true),
            ("WWW.Example.COM.", "cdn.example.com", true),
            ("www.食狮.com.cn", "cdn.xn--85x722f.com.cn", true),
            ("co.uk", "m.co.uk", false),
            ("", "", false),
            ("bad host", "bad host", false),
        ] {
            assert_eq!(same_site(a, b), expected, "{a} / {b}");
        }
    }
    #[test]
    fn public_suffix_is_not_registrable() {
        for host in [
            "com",
            "co.uk",
            "github.io",
            "b.ck",
            "127.0.0.1",
            "[::1]",
            "localhost",
        ] {
            assert_eq!(registrable_domain(host), None, "{host}");
        }
        assert_eq!(
            registrable_domain("m.github.io").as_deref(),
            Some("m.github.io")
        );
        assert_eq!(
            registrable_domain("www.m.github.io").as_deref(),
            Some("m.github.io")
        );
    }
}
