//! Linear parsing of Netscape bookmark HTML. No DOM, scripts, filesystem or network.
//! Folders are flattened because the Android bookmark model is a flat collection.
use std::collections::HashSet;

const MAX_INPUT: usize = 8 * 1024 * 1024;
const MAX_BOOKMARKS: usize = 5_000;

#[derive(Debug, PartialEq)]
pub struct Bookmark {
    pub title: String,
    pub url: String,
}

pub struct Import {
    pub entries: Vec<Bookmark>,
    pub skipped: usize,
}

pub fn parse(input: &str) -> Result<Import, &'static str> {
    if input.len() > MAX_INPUT {
        return Err("Bookmark file is too large");
    }
    let mut entries = Vec::new();
    let mut seen = HashSet::new();
    let mut skipped = 0;
    let mut position = 0;
    let mut anchor: Option<(String, String)> = None;
    let mut hidden = None;
    while position < input.len() {
        let Some(relative) = input[position..].find('<') else {
            break;
        };
        let start = position + relative;
        if hidden.is_none() {
            if let Some((_, title)) = anchor.as_mut() {
                if title.len() < 16 * 1024 {
                    title.push_str(&input[position..start]);
                }
            }
        }
        if input[start..].starts_with("<!--") {
            position = input[start + 4..]
                .find("-->")
                .map(|end| start + 4 + end + 3)
                .unwrap_or(input.len());
            continue;
        }
        let Some(end) = tag_end(input, start + 1) else {
            break;
        };
        position = end + 1;
        let tag = input[start + 1..end].trim();
        let closing = tag.starts_with('/');
        let body = tag.trim_start_matches('/').trim_start();
        let length = body
            .bytes()
            .take_while(|b| b.is_ascii_alphanumeric())
            .count();
        let name = body[..length].to_ascii_lowercase();
        if let Some(hidden_name) = hidden {
            if closing && name == hidden_name {
                hidden = None;
            }
            continue;
        }
        if !closing && (name == "script" || name == "style") {
            hidden = Some(if name == "script" { "script" } else { "style" });
            continue;
        }
        if name != "a" {
            if let Some((_, title)) = anchor.as_mut() {
                if matches!(name.as_str(), "br" | "p") {
                    title.push(' ');
                }
            }
            continue;
        }
        if !closing {
            if anchor.is_some() {
                skipped += 1;
            }
            anchor =
                attribute(&body[length..], "href").map(|url| (decode_entities(url), String::new()));
        } else if let Some((url, title)) = anchor.take() {
            let url = url.trim();
            if url.len() > 8_192 || !crate::is_valid_http_url(url) || !seen.insert(url.to_owned()) {
                skipped += 1;
                continue;
            }
            if entries.len() == MAX_BOOKMARKS {
                return Err("Too many bookmarks");
            }
            let title: String = decode_entities(&title)
                .chars()
                .filter(|c| !c.is_control())
                .take(512)
                .collect();
            entries.push(Bookmark {
                title: if title.trim().is_empty() {
                    url.to_owned()
                } else {
                    title.trim().to_owned()
                },
                url: url.to_owned(),
            });
        }
    }
    Ok(Import { entries, skipped })
}

fn tag_end(input: &str, start: usize) -> Option<usize> {
    let mut quote = 0;
    for (offset, byte) in input.as_bytes()[start..].iter().copied().enumerate() {
        if quote != 0 {
            if byte == quote {
                quote = 0;
            }
        } else if byte == b'\'' || byte == b'"' {
            quote = byte;
        } else if byte == b'>' {
            return Some(start + offset);
        }
    }
    None
}

fn attribute<'a>(mut input: &'a str, target: &str) -> Option<&'a str> {
    while !input.is_empty() {
        input = input.trim_start();
        let length = input
            .bytes()
            .take_while(|b| b.is_ascii_alphanumeric() || b"_-:".contains(b))
            .count();
        if length == 0 {
            return None;
        }
        let name = &input[..length];
        input = input[length..].trim_start();
        if !input.starts_with('=') {
            continue;
        }
        input = input[1..].trim_start();
        let (value, consumed) = match input.as_bytes().first().copied() {
            Some(quote @ (b'\'' | b'"')) => {
                let end = input[1..].find(quote as char)?;
                (&input[1..1 + end], end + 2)
            }
            Some(_) => {
                let end = input.find(char::is_whitespace).unwrap_or(input.len());
                (&input[..end], end)
            }
            None => return None,
        };
        if name.eq_ignore_ascii_case(target) {
            return Some(value);
        }
        input = &input[consumed..];
    }
    None
}

fn decode_entities(input: &str) -> String {
    let mut result = String::with_capacity(input.len());
    let mut rest = input;
    while let Some(start) = rest.find('&') {
        result.push_str(&rest[..start]);
        rest = &rest[start + 1..];
        let end = rest
            .as_bytes()
            .iter()
            .take(16)
            .position(|&byte| byte == b';');
        let decoded = end.and_then(|end| {
            let entity = &rest[..end];
            match entity {
                "amp" => Some('&'),
                "lt" => Some('<'),
                "gt" => Some('>'),
                "quot" => Some('"'),
                "apos" => Some('\''),
                "nbsp" => Some(' '),
                _ => entity
                    .strip_prefix("#x")
                    .or_else(|| entity.strip_prefix("#X"))
                    .and_then(|hex| u32::from_str_radix(hex, 16).ok())
                    .or_else(|| entity.strip_prefix('#').and_then(|n| n.parse().ok()))
                    .and_then(char::from_u32),
            }
        });
        if let Some(character) = decoded {
            result.push(character);
            rest = &rest[end.unwrap() + 1..];
        } else {
            result.push('&');
        }
    }
    result.push_str(rest);
    result
}

fn json_string(value: &str) -> String {
    let mut output = String::from("\"");
    for character in value.chars() {
        match character {
            '"' => output.push_str("\\\""),
            '\\' => output.push_str("\\\\"),
            c if c < ' ' => output.push_str(&format!("\\u{:04x}", c as u32)),
            c => output.push(c),
        }
    }
    output.push('"');
    output
}

impl Import {
    pub fn json(&self) -> String {
        let entries: Vec<String> = self
            .entries
            .iter()
            .map(|b| format!("[{},{}]", json_string(&b.title), json_string(&b.url)))
            .collect();
        format!(
            "{{\"entries\":[{}],\"skipped\":{}}}",
            entries.join(","),
            self.skipped
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn chrome_and_firefox_style_folders_quotes_and_entities() {
        let html = r#"<!DOCTYPE NETSCAPE-Bookmark-file-1><DL><DT><H3>Folder</H3><DL>
        <DT><A ADD_DATE="123" HREF="https://example.com/?x=1&amp;y=2">A &quot;title&quot; 中文</A>
        <DT><a href='https://other.test/a?x=>'>Nested <b>text</b> &#x1F600;</a></DL></DL>"#;
        let result = parse(html).unwrap();
        assert_eq!(result.entries.len(), 2);
        assert_eq!(result.entries[0].url, "https://example.com/?x=1&y=2");
        assert_eq!(result.entries[0].title, "A \"title\" 中文");
        assert_eq!(result.entries[1].title, "Nested text 😀");
        assert!(result.json().contains("\\\"title\\\""));
    }
    #[test]
    fn rejects_active_local_invalid_and_duplicate_urls() {
        let result = parse(r#"<A HREF="&#106;avascript:alert(1)">Bad</A><a href=file:///private/data>Bad</a>
        <a href=https://site.test>First</a><a href=https://site.test>Duplicate</a><a href="https://">Bad</a>"#).unwrap();
        assert_eq!(
            result.entries,
            vec![Bookmark {
                title: "First".into(),
                url: "https://site.test".into()
            }]
        );
        assert_eq!(result.skipped, 4);
    }
    #[test]
    fn ignores_comments_script_style_and_unclosed_anchors() {
        let result = parse(r#"<!-- <a href=https://bad.test>bad</a> --><script>'<a href=https://bad.test>bad</a>'</script>
        <style>a { content: '<a href=https://bad.test>bad</a>'; }</style><a href=https://okay.test>Okay</a><a href=https://unfinished.test>"#).unwrap();
        assert_eq!(result.entries.len(), 1);
        assert_eq!(result.entries[0].url, "https://okay.test");
    }
    #[test]
    fn limits_input_and_record_count_without_partial_import() {
        assert!(parse(&"x".repeat(MAX_INPUT + 1)).is_err());
        let html: String = (0..=MAX_BOOKMARKS)
            .map(|i| format!("<a href=https://site.test/{i}>Page</a>"))
            .collect();
        assert!(parse(&html).is_err());
    }
}
