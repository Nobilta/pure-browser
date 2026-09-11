//! Linear parsing of Netscape bookmark HTML. No DOM, scripts, filesystem or network.
//! Folder paths and empty folders survive round trips, within a bounded tree.
use std::collections::HashSet;

const MAX_INPUT: usize = 8 * 1024 * 1024;
const MAX_BOOKMARKS: usize = 5_000;
const MAX_FOLDERS: usize = 256;
const MAX_DEPTH: usize = 16;

#[derive(Debug, PartialEq)]
pub struct Bookmark {
    pub title: String,
    pub url: String,
    pub folder_path: Vec<String>,
}

pub struct Import {
    pub entries: Vec<Bookmark>,
    pub skipped: usize,
    pub folders: Vec<Vec<String>>,
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
    let mut folder_title: Option<String> = None;
    let mut pending_folder: Option<String> = None;
    let mut path: Vec<String> = Vec::new();
    let mut dl_frames = Vec::new();
    let mut folders = Vec::new();
    let mut seen_folders = HashSet::new();
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
            if let Some(title) = folder_title.as_mut() {
                if title.len() < 4096 {
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
        if name == "h3" {
            if closing {
                pending_folder = folder_title
                    .take()
                    .map(|title| {
                        decode_entities(&title)
                            .chars()
                            .filter(|c| !c.is_control())
                            .take(128)
                            .collect::<String>()
                            .trim()
                            .to_owned()
                    })
                    .filter(|name| !name.is_empty());
            } else {
                folder_title = Some(String::new());
            }
            continue;
        }
        if name == "dl" {
            if closing {
                if dl_frames.pop().unwrap_or(false) {
                    path.pop();
                }
                pending_folder = None;
            } else {
                if dl_frames.len() >= 32 {
                    return Err("Too much HTML nesting");
                }
                let has_folder = pending_folder.is_some();
                if let Some(title) = pending_folder.take() {
                    if path.len() >= MAX_DEPTH {
                        return Err("Too many folder levels");
                    }
                    path.push(title);
                    if seen_folders.insert(path.clone()) {
                        if folders.len() == MAX_FOLDERS {
                            return Err("Too many folders");
                        }
                        folders.push(path.clone());
                    }
                }
                dl_frames.push(has_folder);
            }
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
                folder_path: path.clone(),
            });
        }
    }
    Ok(Import {
        entries,
        skipped,
        folders,
    })
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
            .map(|b| {
                format!(
                    "[{},{},{}]",
                    json_string(&b.title),
                    json_string(&b.url),
                    json_path(&b.folder_path)
                )
            })
            .collect();
        format!(
            "{{\"entries\":[{}],\"skipped\":{},\"folders\":[{}]}}",
            entries.join(","),
            self.skipped,
            self.folders
                .iter()
                .map(|path| json_path(path))
                .collect::<Vec<_>>()
                .join(",")
        )
    }
}

fn json_path(path: &[String]) -> String {
    format!(
        "[{}]",
        path.iter()
            .map(|part| json_string(part))
            .collect::<Vec<_>>()
            .join(",")
    )
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
        assert_eq!(result.entries[0].folder_path, vec!["Folder"]);
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
                url: "https://site.test".into(),
                folder_path: vec![],
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

    #[test]
    fn sibling_nested_and_empty_folders_preserve_paths() {
        let result = parse("<DL><DT><H3>中文</H3><DL><H3>Nested</H3><DL><a href=https://one.test>One</a></DL></DL><H3>Empty</H3><DL></DL><a href=https://root.test>Root</a></DL>").unwrap();
        assert_eq!(result.entries[0].folder_path, vec!["中文", "Nested"]);
        assert!(result.entries[1].folder_path.is_empty());
        assert_eq!(result.folders.len(), 3);
        assert_eq!(result.folders[2], vec!["Empty"]);
        assert!(parse(&"<h3>Deep</h3><dl>".repeat(17)).is_err());
    }

    #[test]
    fn generated_malformed_utf8_html_is_bounded_and_deterministic() {
        let parts = [
            "<DL>",
            "</dl>",
            "<H3>中文&amp;目录</H3>",
            "<a href='https://site.test/文'>",
            "</a>",
            "<script>",
            "</script>",
            "<!--",
            "-->",
            "&#x1f600;",
            "<a href=javascript:alert(1)>",
            "\0",
            "\"<>",
        ];
        let mut seed = 0xbb67ae85u64;
        for _ in 0..2048 {
            let mut input = String::new();
            for _ in 0..40 {
                seed ^= seed << 13;
                seed ^= seed >> 7;
                seed ^= seed << 17;
                input.push_str(parts[seed as usize % parts.len()]);
                input.push_str(&String::from_utf8_lossy(&seed.to_le_bytes()));
            }
            if let Ok(result) = parse(&input) {
                assert!(
                    result.entries.len() <= MAX_BOOKMARKS && result.folders.len() <= MAX_FOLDERS
                );
                assert_eq!(result.entries, parse(&input).unwrap().entries);
                assert!(result
                    .entries
                    .iter()
                    .all(|entry| entry.url.starts_with("https://")
                        && entry.folder_path.len() <= MAX_DEPTH));
            }
        }
    }
}
