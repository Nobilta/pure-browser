//! Native helpers for PSL registrable-domain lookup, Netscape bookmark HTML parsing,
//! and bounded source-code syntax highlighting.

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

pub(crate) fn is_valid_http_url(value: &str) -> bool {
    let Some((scheme, rest)) = value.split_once("://") else {
        return false;
    };
    if !scheme.eq_ignore_ascii_case("http") && !scheme.eq_ignore_ascii_case("https") {
        return false;
    }
    if value.chars().any(|c| c.is_whitespace() || c.is_control()) {
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
        };
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

fn valid_port(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 5
        && value.chars().all(|c| c.is_ascii_digit())
        && value
            .parse::<u32>()
            .map(|port| port <= 65_535)
            .unwrap_or(false)
}

fn is_ipv6_literal(value: &str) -> bool {
    let groups = value.split(':').collect::<Vec<_>>();
    groups.len() >= 3
        && groups.len() <= 8
        && (groups.len() == 8 || groups.iter().any(|group| group.is_empty()))
        && value.contains(':')
        && groups.iter().all(|group| {
            group.is_empty() || (group.len() <= 4 && group.chars().all(|c| c.is_ascii_hexdigit()))
        })
}

#[cfg(test)]
mod tests {
    use super::is_valid_http_url;

    #[test]
    fn rejects_whitespace_and_control_characters() {
        assert!(is_valid_http_url("https://example.com/path?q=1"));
        assert!(!is_valid_http_url("https://example.com/path with space"));
        assert!(!is_valid_http_url("https://example.com/path\u{0007}"));
        assert!(!is_valid_http_url("https://example.com/path\u{007f}"));
    }
}
