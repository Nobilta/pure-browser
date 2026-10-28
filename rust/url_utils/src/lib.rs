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

const FORBIDDEN_HOST_CHARS: &[char] = &['\\', '<', '>', '"', '^', '`', '{', '}', '|', '[', ']'];

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
    let host = if colon_count == 1 {
        let (host, port) = without_user.split_once(':').unwrap_or_default();
        if !valid_port(port) {
            return false;
        }
        host
    } else {
        without_user
    };
    if host.is_empty()
        || host == "."
        || host == ".."
        || host.contains('%')
        || host.chars().any(|c| FORBIDDEN_HOST_CHARS.contains(&c))
    {
        return false;
    }
    true
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
    if value.is_empty() || value.contains('%') || value.contains(":::") {
        return false;
    }
    let compressed = value.find("::");
    if let Some(index) = compressed {
        if value[index + 2..].contains("::") {
            return false;
        }
    }
    let mut ipv4_allowed = true;
    let groups: Vec<&str> = if let Some(index) = compressed {
        let left = &value[..index];
        let right = &value[index + 2..];
        ipv4_allowed = right.contains('.');
        let mut groups = Vec::new();
        if !left.is_empty() {
            groups.extend(left.split(':'));
        }
        if !right.is_empty() {
            groups.extend(right.split(':'));
        }
        groups
    } else {
        value.split(':').collect()
    };
    if groups.is_empty() {
        return compressed.is_some();
    }
    let mut has_ipv4_tail = false;
    for (index, group) in groups.iter().enumerate() {
        if group.is_empty() {
            return false;
        }
        if index == groups.len() - 1 && group.contains('.') {
            if !ipv4_allowed || !is_ipv4_literal(group) {
                return false;
            }
            has_ipv4_tail = true;
        } else if group.len() > 4 || !group.chars().all(|c| c.is_ascii_hexdigit()) {
            return false;
        }
    }
    let units = groups.len() + usize::from(has_ipv4_tail);
    if compressed.is_some() {
        units <= 7
    } else {
        units == 8
    }
}

fn is_ipv4_literal(value: &str) -> bool {
    let parts: Vec<&str> = value.split('.').collect();
    parts.len() == 4
        && parts.iter().all(|part| {
            !part.is_empty()
                && part.len() <= 3
                && part.chars().all(|c| c.is_ascii_digit())
                && part.parse::<u16>().map(|n| n <= 255).unwrap_or(false)
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
        assert!(!is_valid_http_url(" https://example.com"));
        assert!(!is_valid_http_url("https://example.com "));
        assert!(is_valid_http_url("http://[::ffff:192.0.2.1]/list.txt"));
        assert!(is_valid_http_url("http://[2001:db8::192.0.2.1]/"));
        assert!(!is_valid_http_url("http://[::ffff:999.0.0.1]/"));
        assert!(!is_valid_http_url("https://bad|host/"));
        assert!(!is_valid_http_url("https://../"));
        assert!(!is_valid_http_url("https://a[b]/"));
        assert!(!is_valid_http_url("http://[1.2.3.4::]/"));
    }
}
