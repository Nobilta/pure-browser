//! Small, allocation-conscious URL helpers used by the omnibar.
//!
//! This is deliberately a conservative parser rather than a browser URL implementation:
//! Chromium remains responsible for actually parsing and loading URLs. The native side
//! only answers the URL-vs-search question and extracts a display domain. Keeping the
//! rules identical to the Kotlin fallback is more important than shaving a few
//! instructions from a path that runs once per navigation.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

mod bookmarks;
mod source;

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_core_UrlUtils_nativeRegistrableDomain(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
) -> jstring {
    let host: String = match env.get_string(&host) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let Some(domain) = site_identity::registrable_domain(&host) else {
        return std::ptr::null_mut();
    };
    env.new_string(domain)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_data_BookmarkHtml_nativeParse(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let input: String = match env.get_string(&input) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    // The size limit lives in bookmarks::parse: this entry point receives whole bookmark
    // documents (not single URLs), so the app-side cap and the parser's cap must agree.
    let Ok(parsed) = bookmarks::parse(&input) else {
        return std::ptr::null_mut();
    };
    env.new_string(parsed.json())
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

const INTERNAL_SCHEMES: &[&str] = &["http", "https", "about"];

// Schemes that the omnibar is allowed to hand to another application.  Keep this list in
// lock-step with UrlUtils.kt and ExternalIntentHandler: an opaque scheme is an app-launch
// boundary, so accepting a new value in only one of the two implementations would make the
// visible "访问/搜索" affordance lie about what submit actually does.
const EXTERNAL_SCHEMES: &[&str] = &[
    "tel",
    "sms",
    "smsto",
    "mailto",
    "geo",
    "market",
    "intent",
    "android-app",
    "weixin",
    "alipays",
    "alipay",
    "mqq",
    "mqqapi",
    "tbopen",
    "taobao",
    "openapp.jdmobile",
    "jdmobile",
    "pinduoduo",
    "bilibili",
    "zhihu",
    "baiduboxapp",
    "sinaweibo",
    "snssdk1128",
    "kwai",
];

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_core_UrlUtils_nativeNormalize(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
    search_template: JString,
) -> jstring {
    let input: String = match env.get_string(&input) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let template: String = match env.get_string(&search_template) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let result = normalize_or_search(input.trim(), &template);
    env.new_string(result)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn normalize_or_search(input: &str, search_template: &str) -> String {
    if input.is_empty() {
        return "about:blank".to_string();
    }

    // Keep malformed web-looking values out of the direct-navigation path. The Kotlin
    // caller applies the same guard, but doing it here keeps the JNI helper safe for any
    // future caller as well.
    let scheme = input
        .split_once(':')
        .map(|(value, _)| value.to_ascii_lowercase())
        .unwrap_or_default();
    if (scheme == "http" || scheme == "https") && !is_valid_http_url(input) {
        return build_search(input, search_template);
    }
    if (scheme == "about" || EXTERNAL_SCHEMES.contains(&scheme.as_str()))
        && !has_opaque_payload(input)
    {
        return build_search(input, search_template);
    }

    // A scheme is authoritative, except for the host:port shape (localhost:8080 and
    // friends) which otherwise looks like an opaque scheme to a URI parser.
    if has_scheme(input) && !looks_like_host_port(input) {
        if looks_like_invalid_host_port(input) {
            return build_search(input, search_template);
        }
        // Only web/about and the explicit external allow-list are direct omnibar
        // destinations.  In particular, do not return javascript:, data:, file:, ftp:, or
        // an arbitrary app protocol: callers may use this helper without the higher-level
        // NavigationPolicy safety check.
        if is_direct_scheme(&scheme) {
            return input.to_string();
        }
        return build_search(input, search_template);
    }
    if looks_like_host(input) || looks_like_host_port(input) {
        return format!("https://{input}");
    }

    build_search(input, search_template)
}

fn build_search(input: &str, search_template: &str) -> String {
    let encoded = percent_encode(input);
    if search_template.contains("%s") {
        search_template.replace("%s", &encoded)
    } else if search_template.contains("{query}") {
        search_template.replace("{query}", &encoded)
    } else {
        // A malformed custom template should still produce a useful search URL rather
        // than silently dropping the query.
        format!("{search_template}{encoded}")
    }
}

fn is_direct_scheme(scheme: &str) -> bool {
    INTERNAL_SCHEMES.contains(&scheme) || EXTERNAL_SCHEMES.contains(&scheme)
}

fn has_opaque_payload(value: &str) -> bool {
    value
        .split_once(':')
        .map(|(_, rest)| {
            !rest.is_empty() && !rest.chars().any(|c| c.is_whitespace() || c.is_control())
        })
        .unwrap_or(false)
}

fn has_scheme(value: &str) -> bool {
    let Some((scheme, _)) = value.split_once(':') else {
        return false;
    };
    !scheme.is_empty()
        && scheme.chars().enumerate().all(|(index, c)| {
            if index == 0 {
                c.is_ascii_alphabetic()
            } else {
                c.is_ascii_alphanumeric() || matches!(c, '+' | '-' | '.')
            }
        })
}

fn looks_like_host_port(value: &str) -> bool {
    if let Some(close) = value.strip_prefix('[').and_then(|v| v.find(']')) {
        let close = close + 1;
        // `close` is the original index of `]` (the search happened on the string
        // after `[`). Keep the body and suffix slices explicit; an off-by-one here
        // would silently route bracketed IPv6 values through the generic search branch.
        if !is_ipv6_literal(&value[1..close]) {
            return false;
        }
        let rest = &value[close + 1..];
        let (port, suffix) = match rest.strip_prefix(':').map(|v| {
            let end = v.find(['/', '?', '#']).unwrap_or(v.len());
            (&v[..end], &v[end..])
        }) {
            Some(parts) => parts,
            None => return false,
        };
        return valid_port(port)
            && (suffix.is_empty()
                || matches!(suffix.as_bytes().first(), Some(b'/' | b'?' | b'#')));
    }
    let authority = value.split(['/', '?', '#']).next().unwrap_or_default();
    let Some((host, port)) = authority.split_once(':') else {
        return false;
    };
    valid_port(port) && !is_direct_scheme(&host.to_ascii_lowercase()) && is_host_name(host)
}

fn valid_port(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 5
        && value.chars().all(|c| c.is_ascii_digit())
        && value
            .parse::<u32>()
            .map(|port| port <= 65_535)
            .unwrap_or(false)
}

fn looks_like_invalid_host_port(value: &str) -> bool {
    let authority = value.split(['/', '?', '#']).next().unwrap_or_default();
    if let Some(rest) = authority.strip_prefix('[') {
        let Some(relative_close) = rest.find(']') else {
            return false;
        };
        let close = relative_close + 1;
        let host = &authority[..=close];
        let suffix = &authority[close + 1..];
        if !is_ipv6_literal(&host[1..close]) || !suffix.starts_with(':') {
            return false;
        }
        return !valid_port(&suffix[1..]);
    }
    let Some((host, port)) = authority.split_once(':') else {
        return false;
    };
    if host.is_empty() || is_direct_scheme(&host.to_ascii_lowercase()) {
        return false;
    }
    let hostish = if host.starts_with('[') {
        host.ends_with(']') && is_ipv6_literal(&host[1..host.len() - 1])
    } else {
        is_host_name(host)
    };
    hostish && !valid_port(port)
}

fn looks_like_host(value: &str) -> bool {
    if value.is_empty() || value.chars().any(char::is_whitespace) {
        return false;
    }
    let authority = value.split(['/', '?', '#']).next().unwrap_or_default();
    if authority.is_empty() {
        return false;
    }
    let without_user = authority.rsplit('@').next().unwrap_or(authority);
    let host = if let Some(rest) = without_user.strip_prefix('[') {
        let Some(close) = rest.find(']') else {
            return false;
        };
        if !is_ipv6_literal(&rest[..close]) {
            return false;
        }
        let suffix = &rest[close + 1..];
        if !suffix.is_empty() && (!suffix.starts_with(':') || !valid_port(&suffix[1..])) {
            return false;
        }
        return true;
    } else {
        let colon_count = without_user.chars().filter(|c| *c == ':').count();
        if colon_count > 1 {
            return false;
        }
        if colon_count == 1 {
            let (host, port) = without_user.split_once(':').unwrap_or_default();
            if !valid_port(port) {
                return false;
            }
            host
        } else {
            without_user
        }
    };
    is_host_name(host)
}

fn is_host_name(host: &str) -> bool {
    if host.eq_ignore_ascii_case("localhost") {
        return true;
    }
    if is_ipv4(host) {
        return true;
    }
    if !host.contains('.') || host.ends_with('.') {
        return false;
    }
    let labels: Vec<&str> = host.split('.').collect();
    let tld = labels.last().copied().unwrap_or_default();
    tld.len() >= 2
        && tld.chars().all(|c| c.is_ascii_alphabetic())
        && labels.iter().all(|label| {
            !label.is_empty()
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
        })
}

fn is_ipv4(value: &str) -> bool {
    let parts: Vec<&str> = value.split('.').collect();
    parts.len() == 4
        && parts.iter().all(|part| {
            !part.is_empty()
                && part.len() <= 3
                && part
                    .parse::<u16>()
                    .map(|number| number <= 255)
                    .unwrap_or(false)
        })
}

fn is_ipv6_literal(value: &str) -> bool {
    // Keep this intentionally conservative: a bracketed value containing at least two
    // hexadecimal/colon groups is enough for URL-vs-search classification, while the
    // platform URL parser remains responsible for full RFC 4291 validation.
    let groups = value.split(':').collect::<Vec<_>>();
    groups.len() >= 3
        && groups.len() <= 8
        && (groups.len() == 8 || groups.iter().any(|group| group.is_empty()))
        && value.contains(':')
        && groups.iter().all(|group| {
            group.is_empty() || (group.len() <= 4 && group.chars().all(|c| c.is_ascii_hexdigit()))
        })
}

fn is_valid_http_url(value: &str) -> bool {
    let Some((scheme, rest)) = value.split_once("://") else {
        return false;
    };
    if !scheme.eq_ignore_ascii_case("http") && !scheme.eq_ignore_ascii_case("https") {
        return false;
    }
    if value.chars().any(char::is_whitespace) {
        return false;
    }
    let authority = rest.split(['/', '?', '#']).next().unwrap_or_default();
    if authority.is_empty() {
        return false;
    }
    let without_user = authority.rsplit('@').next().unwrap_or(authority);
    if without_user.starts_with('[') {
        let Some(close) = without_user.find(']') else {
            return false;
        };
        if !is_ipv6_literal(&without_user[1..close]) {
            return false;
        }
        let suffix = &without_user[close + 1..];
        return suffix.is_empty() || (suffix.starts_with(':') && valid_port(&suffix[1..]));
    }
    let colon_count = without_user.chars().filter(|c| *c == ':').count();
    if colon_count > 1 {
        return false;
    }
    if colon_count == 1 {
        let (host, port) = without_user.split_once(':').unwrap_or_default();
        !host.is_empty() && valid_port(port)
    } else {
        !without_user.is_empty()
    }
}

fn percent_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.as_bytes() {
        match byte {
            // Match android.net.Uri.encode's default allow-list. Keeping this identical to
            // the Kotlin fallback prevents a native build from changing the search URL for
            // punctuation such as parentheses or apostrophes.
            b'A'..=b'Z'
            | b'a'..=b'z'
            | b'0'..=b'9'
            | b'-'
            | b'_'
            | b'.'
            | b'!'
            | b'~'
            | b'*'
            | b'\''
            | b'('
            | b')' => out.push(*byte as char),
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_and_search_decisions() {
        assert_eq!(
            normalize_or_search("example.com", "https://x/?q=%s"),
            "https://example.com"
        );
        assert_eq!(
            normalize_or_search("localhost:8080", "https://x/?q=%s"),
            "https://localhost:8080"
        );
        assert_eq!(
            normalize_or_search("a+b & c", "https://x/?q=%s"),
            "https://x/?q=a%2Bb%20%26%20c"
        );
        assert_eq!(
            normalize_or_search("mailto:a@b.com", "https://x/?q=%s"),
            "mailto:a@b.com"
        );
    }

    #[test]
    fn bracketed_ipv6_with_port_is_a_host() {
        assert_eq!(
            normalize_or_search("[::1]:3000", "https://x/?q=%s"),
            "https://[::1]:3000"
        );
        assert_eq!(
            normalize_or_search("[2001:db8:0:0:0:0:0:1]:443", "https://x/?q=%s"),
            "https://[2001:db8:0:0:0:0:0:1]:443"
        );
    }

    #[test]
    fn malformed_http_and_impossible_ports_are_searches() {
        assert_eq!(
            normalize_or_search("https://", "https://x/?q=%s"),
            "https://x/?q=https%3A%2F%2F"
        );
        assert_eq!(
            normalize_or_search("example.com:65536", "https://x/?q=%s"),
            "https://x/?q=example.com%3A65536"
        );
    }

    #[test]
    fn opaque_numeric_schemes_are_not_hosts() {
        assert_eq!(
            normalize_or_search("tel:1234", "https://x/?q=%s"),
            "tel:1234"
        );
        assert_eq!(
            normalize_or_search("mailto:1234", "https://x/?q=%s"),
            "mailto:1234"
        );
    }

    #[test]
    fn unsafe_and_unknown_schemes_become_searches() {
        for input in [
            "javascript:alert(1)",
            "data:text/html,<h1>x</h1>",
            "file:///sdcard/private.txt",
            "ftp://example.com/file",
            "evilapp://takeover",
        ] {
            assert_eq!(
                normalize_or_search(input, "https://x/?q=%s"),
                format!("https://x/?q={}", percent_encode(input)),
                "unexpected direct navigation for {input}",
            );
        }
    }

    #[test]
    fn allowlisted_external_schemes_are_preserved_only_with_payload() {
        assert_eq!(
            normalize_or_search("MAILTO:user@example.com", "https://x/?q=%s"),
            "MAILTO:user@example.com"
        );
        assert_eq!(
            normalize_or_search("mailto:", "https://x/?q=%s"),
            "https://x/?q=mailto%3A"
        );
    }

    #[test]
    fn numeric_port_carve_out_requires_a_real_host() {
        assert_eq!(
            normalize_or_search("example.com:8443", "https://x/?q=%s"),
            "https://example.com:8443"
        );
        assert_eq!(
            normalize_or_search("evilapp:1234", "https://x/?q=%s"),
            "https://x/?q=evilapp%3A1234"
        );
    }
}
