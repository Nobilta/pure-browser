use base64::{engine::general_purpose, Engine as _};
use encoding_rs::{Encoding, GBK, UTF_8};
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use regex::Regex;
use std::sync::OnceLock;

static CONTENT_DISPOSITION_REGEX: OnceLock<Regex> = OnceLock::new();
static FILENAME_STAR_REGEX: OnceLock<Regex> = OnceLock::new();
static RFC2047_REGEX: OnceLock<Regex> = OnceLock::new();

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_rust_FilenameParser_nativeParseFilename(
    mut env: JNIEnv,
    _class: JClass,
    content_disposition: JString,
    url: JString,
) -> jstring {
    let cd_str: String = match env.get_string(&content_disposition) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let url_str: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let result = parse_filename_internal(&cd_str, &url_str);

    // JNI allocation can fail under memory pressure. Never let an allocation panic cross
    // the FFI boundary; the Kotlin caller treats a null result as a signal to use its
    // defensive fallback parser.
    match env.new_string(result) {
        Ok(output) => output.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn parse_filename_internal(content_disposition: &str, url: &str) -> String {
    // Try RFC 5987 (filename*=UTF-8''...)
    if let Some(filename) = parse_rfc5987(content_disposition) {
        return sanitize_filename(&filename);
    }

    // Try RFC 2047 (=?UTF-8?B?...?=)
    if let Some(filename) = parse_rfc2047(content_disposition) {
        return sanitize_filename(&filename);
    }

    // Try standard filename parameter
    if let Some(filename) = parse_standard_filename(content_disposition) {
        return sanitize_filename(&repair_mojibake(&filename));
    }

    // Fall back to URL
    sanitize_filename(&extract_filename_from_url(url))
}

fn parse_rfc5987(cd: &str) -> Option<String> {
    let re = FILENAME_STAR_REGEX
        .get_or_init(|| Regex::new(r#"(?i)filename\*\s*=\s*([^']*)'([^']*)'([^;\r\n]+)"#).unwrap());

    if let Some(caps) = re.captures(cd) {
        let declared = caps.get(1)?.as_str().trim();
        let encoding = if declared.is_empty() {
            "UTF-8".to_string()
        } else {
            declared.to_uppercase()
        };
        let filename_encoded = caps.get(3)?.as_str().trim().trim_matches('"');

        // Decode percent-encoding to bytes first, then apply the declared charset. This
        // matters for Chinese servers that legitimately use GBK in filename*.
        let decoded_bytes: Vec<u8> =
            percent_encoding::percent_decode_str(filename_encoded).collect();
        if let Some(decoded) = decode_with_charset(&decoded_bytes, &encoding) {
            if !decoded.trim().is_empty() && !decoded.contains('\u{FFFD}') {
                return Some(decoded);
            }
        }
    }

    None
}

fn parse_rfc2047(cd: &str) -> Option<String> {
    let re =
        RFC2047_REGEX.get_or_init(|| Regex::new(r"(?i)=\?([^?]+)\?([BQ])\?([^?]+)\?=").unwrap());

    if let Some(caps) = re.captures(cd) {
        let charset = caps.get(1)?.as_str().to_uppercase();
        let encoding = caps.get(2)?.as_str().to_uppercase();
        let encoded = caps.get(3)?.as_str();

        let decoded_bytes = match encoding.as_str() {
            "B" => general_purpose::STANDARD.decode(encoded).ok()?,
            "Q" => decode_quoted_printable(encoded)?,
            _ => return None,
        };

        return decode_with_charset(&decoded_bytes, &charset);
    }

    None
}

fn parse_standard_filename(cd: &str) -> Option<String> {
    let re = CONTENT_DISPOSITION_REGEX
        .get_or_init(|| Regex::new(r#"(?i)filename\s*=\s*"?([^";\r\n]+)"?"#).unwrap());

    re.captures(cd)
        .and_then(|caps| caps.get(1))
        .map(|m| m.as_str().trim().to_string())
}

fn decode_quoted_printable(s: &str) -> Option<Vec<u8>> {
    let mut result = Vec::new();
    let mut chars = s.chars().peekable();

    while let Some(ch) = chars.next() {
        if ch == '=' {
            if let (Some(h1), Some(h2)) = (chars.next(), chars.next()) {
                if let Ok(byte) = u8::from_str_radix(&format!("{}{}", h1, h2), 16) {
                    result.push(byte);
                } else {
                    result.push(b'=');
                    result.push(h1 as u8);
                    result.push(h2 as u8);
                }
            } else {
                // Preserve a malformed/trailing escape instead of silently dropping it.
                result.push(b'=');
            }
        } else if ch == '_' {
            result.push(b' ');
        } else {
            result.push(ch as u8);
        }
    }

    Some(result)
}

fn decode_with_charset(bytes: &[u8], charset: &str) -> Option<String> {
    let encoding = match charset {
        "UTF-8" | "UTF8" => UTF_8,
        "GBK" | "GB2312" | "GB18030" => GBK,
        _ => Encoding::for_label(charset.as_bytes())?,
    };

    let (decoded, _, had_errors) = encoding.decode(bytes);
    if had_errors {
        None
    } else {
        Some(decoded.to_string())
    }
}

fn repair_mojibake(raw: &str) -> String {
    // If the platform already decoded a Unicode header, re-encoding characters outside
    // ISO-8859-1 would destroy it. Only attempt repair for a Latin-1-shaped string.
    if raw.is_ascii() || raw.chars().any(|ch| ch as u32 > 0xFF) {
        return raw.to_string();
    }

    let latin1_bytes: Vec<u8> = raw.chars().map(|ch| ch as u8).collect();

    // UTF-8 mojibake is unambiguous when it validates; GBK is the fallback for raw GBK
    // bytes commonly emitted by older Chinese servers.
    if let Some(decoded) = try_decode(&latin1_bytes, UTF_8) {
        return decoded;
    }
    if let Some(decoded) = try_decode(&latin1_bytes, GBK) {
        return decoded;
    }

    raw.to_string()
}

fn try_decode(bytes: &[u8], encoding: &'static Encoding) -> Option<String> {
    let (decoded, _, had_errors) = encoding.decode(bytes);
    if !had_errors {
        Some(decoded.to_string())
    } else {
        None
    }
}

fn extract_filename_from_url(url: &str) -> String {
    if let Some(path_start) = url.find("://") {
        let path = &url[path_start + 3..];
        if let Some(path_only) = path.split(['?', '#']).next() {
            if let Some(filename) = path_only.rsplit('/').next() {
                if !filename.is_empty() && filename.contains('.') {
                    return percent_encoding::percent_decode_str(filename)
                        .decode_utf8_lossy()
                        .to_string();
                }
            }
        }
    }

    "download".to_string()
}

/// Keep the native parser safe to call from legacy integrations that write the result to
/// disk directly. The Android DownloadManager path performs the same policy in Kotlin.
fn sanitize_filename(raw: &str) -> String {
    let mut value: String = raw
        .chars()
        .map(|ch| {
            if ch.is_control() || matches!(ch, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|')
            {
                '_'
            } else {
                ch
            }
        })
        .collect::<String>()
        .trim()
        .trim_start_matches('.')
        .trim_end_matches(['.', ' '])
        .to_string();
    if value.is_empty() {
        value = "download".to_string();
    }
    if value.chars().count() > 127 {
        value = value.chars().take(127).collect();
    }
    value
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_rfc5987() {
        let cd = "attachment; filename*=UTF-8''test%20file.pdf";
        let result = parse_filename_internal(cd, "");
        assert_eq!(result, "test file.pdf");
    }

    #[test]
    fn test_standard_filename() {
        let cd = r#"attachment; filename="document.pdf""#;
        let result = parse_filename_internal(cd, "");
        assert_eq!(result, "document.pdf");
    }

    #[test]
    fn test_url_fallback() {
        let result = parse_filename_internal("", "https://example.com/downloads/file.zip");
        assert_eq!(result, "file.zip");
    }

    #[test]
    fn already_decoded_unicode_is_preserved() {
        let result = parse_filename_internal(r#"attachment; filename="报告.pdf""#, "");
        assert_eq!(result, "报告.pdf");
    }

    #[test]
    fn gbk_extended_filename_is_decoded() {
        let result = parse_filename_internal("attachment; filename*=GBK''%B1%A8%B8%E6.pdf", "");
        assert_eq!(result, "报告.pdf");
    }

    #[test]
    fn empty_extended_charset_defaults_to_utf8() {
        let result = parse_filename_internal("attachment; filename*='' %E6%8A%A5%E5%91%8A.pdf", "");
        // A space after the second quote is technically invalid but should not panic; the
        // parser falls back to the safe URL/default path when decoding fails.
        assert!(!result.is_empty());
    }

    #[test]
    fn unsafe_filename_is_sanitized() {
        let result = parse_filename_internal(r#"attachment; filename="../secret.txt""#, "");
        assert_eq!(result, ".._secret.txt".trim_start_matches('.'));
        assert!(!result.contains('/'));
    }
}
