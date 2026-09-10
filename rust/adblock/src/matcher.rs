//! Domain and required-literal indices for network blocking rules and exceptions.
//! Only candidates whose host or three-byte literal occurs in the request reach the
//! wildcard matcher. Unanchored wildcards use one bounded dynamic-programming pass.

use crate::rule::{Anchor, ResourceType, Rule, SkipReason};
use std::collections::{HashMap, HashSet};

#[derive(Default)]
pub struct Matcher {
    blocking: RuleSet,
    exceptions: RuleSet,
}

#[derive(Debug)]
pub struct DocumentContext {
    host: String,
    site: Option<site_identity::SiteKey>,
}

impl DocumentContext {
    pub fn new(url: &str) -> Self {
        let lower = url.to_lowercase();
        let host = extract_host(&lower).unwrap_or("").to_owned();
        let site = site_identity::SiteKey::new(&host);
        Self { host, site }
    }
}

/// An index is only a prefilter: complete patterns and options always decide the result.
#[derive(Default)]
struct RuleSet {
    domains: HashMap<String, Vec<Rule>>,
    tokens: HashMap<[u8; 3], Vec<TokenRule>>,
    fallback: Vec<Rule>,
    count: usize,
}

struct TokenRule {
    rule: Rule,
    literal: String,
}

struct Request<'a> {
    original: &'a str,
    lower: &'a str,
    host: &'a str,
    first_party_host: &'a str,
    resource_type: ResourceType,
    is_third_party: bool,
}

impl Request<'_> {
    fn matches(&self, rule: &Rule) -> bool {
        rule.options_apply(
            self.resource_type,
            self.first_party_host,
            self.is_third_party,
        ) && pattern_matches(
            &rule.pattern,
            rule.anchor,
            rule.anchor_end,
            self.url_for(rule),
        )
    }

    fn url_for(&self, rule: &Rule) -> &str {
        if rule.options.match_case {
            self.original
        } else {
            self.lower
        }
    }
}

impl RuleSet {
    fn add(&mut self, rule: Rule) {
        self.count += 1;
        if rule.anchor == Anchor::Domain {
            let host = rule
                .pattern
                .split(['/', '^'])
                .next()
                .unwrap_or(&rule.pattern);
            if is_indexable_domain_host(host) {
                // Case-sensitive paths still need a lowercase key for the host lookup.
                self.domains
                    .entry(host.to_ascii_lowercase())
                    .or_default()
                    .push(rule);
                return;
            }
        }
        if let Some(literal) = extract_token(&rule.pattern) {
            // Pick the least populated triplet, avoiding common prefixes such as "http".
            // Every triplet in this literal is necessary, including when it occurs inside
            // a longer URL word. Splitting the URL into words would miss such matches.
            let key = literal
                .as_bytes()
                .windows(3)
                .map(|bytes| {
                    [
                        bytes[0].to_ascii_lowercase(),
                        bytes[1].to_ascii_lowercase(),
                        bytes[2].to_ascii_lowercase(),
                    ]
                })
                .min_by_key(|key| self.tokens.get(key).map_or(0, Vec::len))
                .expect("extracted tokens have at least three bytes");
            self.tokens
                .entry(key)
                .or_default()
                .push(TokenRule { rule, literal });
        } else {
            self.fallback.push(rule);
        }
    }

    fn matches(&self, request: &Request<'_>) -> bool {
        for suffix in std::iter::once(request.host).chain(
            request
                .host
                .match_indices('.')
                .map(|(offset, _)| &request.host[offset + 1..]),
        ) {
            if self
                .domains
                .get(suffix)
                .is_some_and(|rules| rules.iter().any(|rule| request.matches(rule)))
            {
                return true;
            }
        }
        // Only matching buckets enter this set. Repeated text in long signed URLs must
        // not run the same wildcard rule hundreds of times.
        let mut visited = HashSet::new();
        for bytes in request.lower.as_bytes().windows(3) {
            let key = [bytes[0], bytes[1], bytes[2]];
            if let Some(rules) = self.tokens.get(&key) {
                if visited.insert(key)
                    && rules.iter().any(|entry| {
                        request.url_for(&entry.rule).contains(&entry.literal)
                            && request.matches(&entry.rule)
                    })
                {
                    return true;
                }
            }
        }
        self.fallback.iter().any(|rule| request.matches(rule))
    }
}

#[derive(Default, Debug)]
pub struct LoadStats {
    pub total_lines: usize,
    pub blocking_rules: usize,
    pub exceptions: usize,
    pub skipped_blank: usize,
    pub skipped_comment: usize,
    pub skipped_cosmetic: usize,
    pub skipped_unsupported: usize,
}

impl Matcher {
    pub fn new() -> Self {
        Self::default()
    }

    /// Appends validated rules, allowing several subscriptions to share one snapshot.
    pub fn load(&mut self, content: &str) -> LoadStats {
        let mut stats = LoadStats::default();
        for line in content.lines() {
            stats.total_lines += 1;
            if line.len() > MAX_RULE_LENGTH {
                stats.skipped_unsupported += 1;
                continue;
            }
            match Rule::parse(line) {
                Ok(rule) if rule.is_exception => {
                    stats.exceptions += 1;
                    self.exceptions.add(rule);
                }
                Ok(rule) => {
                    stats.blocking_rules += 1;
                    self.blocking.add(rule);
                }
                Err(SkipReason::Blank) => stats.skipped_blank += 1,
                Err(SkipReason::Comment) => stats.skipped_comment += 1,
                Err(SkipReason::Cosmetic) => stats.skipped_cosmetic += 1,
                Err(SkipReason::Unsupported) => stats.skipped_unsupported += 1,
            }
        }
        stats
    }

    pub fn should_block(&self, url: &str, first_party: &str, resource_type: ResourceType) -> bool {
        self.should_block_context(url, &DocumentContext::new(first_party), resource_type)
    }

    pub fn should_block_context(
        &self,
        url: &str,
        document: &DocumentContext,
        resource_type: ResourceType,
    ) -> bool {
        if !is_http_url(url) {
            return false;
        }
        let lower = url.to_lowercase();
        let host = extract_host(&lower).unwrap_or("");
        let first_party_host = document.host.as_str();
        let request = Request {
            original: url,
            lower: &lower,
            host,
            first_party_host,
            resource_type,
            is_third_party: !first_party_host.is_empty()
                && !document
                    .site
                    .as_ref()
                    .is_some_and(|site| site.contains(host)),
        };
        self.blocking.matches(&request) && !self.exceptions.matches(&request)
    }

    pub fn rule_count(&self) -> usize {
        self.blocking.count + self.exceptions.count
    }

    /// Only used on explicit diagnosis; no explanation strings are allocated by requests.
    pub fn explain(
        content: &str,
        url: &str,
        page: &str,
        resource_type: ResourceType,
    ) -> (Option<String>, Option<String>) {
        if !is_http_url(url) {
            return (None, None);
        }
        let lower = url.to_lowercase();
        let document = DocumentContext::new(page);
        let host = extract_host(&lower).unwrap_or("");
        let request = Request {
            original: url,
            lower: &lower,
            host,
            first_party_host: &document.host,
            resource_type,
            is_third_party: !document.host.is_empty()
                && !document
                    .site
                    .as_ref()
                    .is_some_and(|site| site.contains(host)),
        };
        let mut blocking = None;
        let mut exception = None;
        for line in content.lines().filter(|line| line.len() <= MAX_RULE_LENGTH) {
            if let Ok(rule) = Rule::parse(line) {
                if request.matches(&rule) {
                    if rule.is_exception {
                        exception.get_or_insert_with(|| line.trim().to_owned());
                    } else {
                        blocking.get_or_insert_with(|| line.trim().to_owned());
                    }
                    if blocking.is_some() && exception.is_some() {
                        break;
                    }
                }
            }
        }
        (blocking, exception)
    }
}

/// True if `pattern` (with EasyList wildcards `*` and separator `^`) matches `url`.
///
/// `^` means "not a letter/digit/`._-`" — typically `/`, `?`, `&`, `:` — so it anchors at
/// boundaries without naming the exact character.
fn pattern_matches(pattern: &str, anchor: Anchor, anchor_end: bool, url: &str) -> bool {
    let pat = pattern.as_bytes();
    let bytes = url.as_bytes();
    match anchor {
        Anchor::Start => wildcard_at(pat, bytes, 0, anchor_end),
        Anchor::None => {
            if !pat.contains(&b'*') {
                if !pat.contains(&b'^') {
                    return if anchor_end {
                        url.ends_with(pattern)
                    } else {
                        url.contains(pattern)
                    };
                }
                return (0..=bytes.len()).any(|start| literal_at(pat, bytes, start, anchor_end));
            }
            wildcard_match(pat, bytes, anchor_end, true)
        }
        Anchor::Domain => {
            let after_scheme = if url
                .get(..7)
                .is_some_and(|v| v.eq_ignore_ascii_case("http://"))
            {
                &url[7..]
            } else if url
                .get(..8)
                .is_some_and(|v| v.eq_ignore_ascii_case("https://"))
            {
                &url[8..]
            } else {
                return false;
            };
            let authority_end = after_scheme
                .find(['/', '?', '#'])
                .unwrap_or(after_scheme.len());
            let authority = &after_scheme[..authority_end];
            let host_port = authority.rsplit('@').next().unwrap_or(authority);
            let host_offset = authority.len() - host_port.len();
            let host = host_port
                .strip_prefix('[')
                .and_then(|v| v.find(']').map(|end| &host_port[..end + 2]))
                .unwrap_or_else(|| host_port.split(':').next().unwrap_or(host_port));
            let rule_authority = pattern
                .split(['/', '^'])
                .next()
                .unwrap_or(pattern)
                .trim_end_matches('|');
            let rule_host = rule_authority
                .strip_prefix('[')
                .and_then(|v| v.find(']').map(|end| &rule_authority[..end + 2]))
                .unwrap_or_else(|| rule_authority.split(':').next().unwrap_or(rule_authority));
            // Compare host names here, including explicit host wildcards. Ports are
            // checked by the full-pattern match, not by the hostname guard.
            let authority_offset = url.len() - after_scheme.len();
            for pos in std::iter::once(0).chain(
                host.bytes()
                    .enumerate()
                    .filter_map(|(i, b)| (b == b'.').then_some(i + 1)),
            ) {
                let host_matches = wildcard_at(rule_host.as_bytes(), host.as_bytes(), pos, true)
                    || (rule_authority.len() == pattern.len()
                        && rule_host.split_once('*').is_some_and(|(prefix, _)| {
                            !prefix.is_empty() && host[pos..] == *prefix
                        }));
                if host_matches
                    && wildcard_at(pat, bytes, authority_offset + host_offset + pos, anchor_end)
                {
                    return true;
                }
            }
            false
        }
    }
}

/// Literal/separator patterns need no allocation. `^` can consume zero bytes only at EOF.
fn literal_at(pat: &[u8], url: &[u8], start: usize, anchor_end: bool) -> bool {
    let mut position = start;
    for &token in pat {
        if token == b'^' {
            if position == url.len() {
                continue;
            }
            if !is_separator(url[position]) {
                return false;
            }
        } else if url.get(position) != Some(&token) {
            return false;
        }
        position += 1;
    }
    !anchor_end || position == url.len()
}

fn is_separator(byte: u8) -> bool {
    !byte.is_ascii_alphanumeric() && !matches!(byte, b'.' | b'-' | b'_')
}

fn wildcard_at(pat: &[u8], url: &[u8], start: usize, anchor_end: bool) -> bool {
    if start > url.len() {
        return false;
    }
    if !pat.contains(&b'*') {
        return literal_at(pat, url, start, anchor_end);
    }
    wildcard_match(pat, &url[start..], anchor_end, false)
}

/// O(pattern × URL) time and one O(URL) row, including unanchored patterns. The old
/// unanchored path rebuilt this table at every URL offset, becoming quadratic in URL
/// length and allocating a fresh row for every pattern byte at every offset.
fn wildcard_match(pat: &[u8], url: &[u8], anchor_end: bool, anywhere: bool) -> bool {
    let width = url.len();
    let mut row = vec![!anchor_end; width + 1];
    row[width] = true;
    for &token in pat.iter().rev() {
        match token {
            b'*' => {
                for position in (0..width).rev() {
                    row[position] = row[position] || row[position + 1];
                }
            }
            b'^' => {
                for position in 0..width {
                    row[position] = is_separator(url[position]) && row[position + 1];
                }
                // At EOF the separator is zero-width, so row[width] is unchanged.
            }
            literal => {
                for position in 0..width {
                    row[position] = url[position] == literal && row[position + 1];
                }
                row[width] = false;
            }
        }
    }
    if anywhere {
        row.into_iter().any(|matched| matched)
    } else {
        row[0]
    }
}

pub(crate) fn extract_host(url: &str) -> Option<&str> {
    let after = url
        .strip_prefix("http://")
        .or_else(|| url.strip_prefix("https://"))?;
    let end = after.find(&['/', '?', '#'][..]).unwrap_or(after.len());
    let authority = after[..end].rsplit('@').next().unwrap_or(&after[..end]);
    if authority.starts_with('[') {
        let close = authority.find(']')?;
        Some(&authority[1..close])
    } else {
        Some(authority.split(':').next().unwrap_or(authority))
    }
}

fn is_http_url(url: &str) -> bool {
    url.get(..7)
        .map(|prefix| prefix.eq_ignore_ascii_case("http://"))
        .unwrap_or(false)
        || url
            .get(..8)
            .map(|prefix| prefix.eq_ignore_ascii_case("https://"))
            .unwrap_or(false)
}

const MAX_RULE_LENGTH: usize = 16 * 1024;

fn is_indexable_domain_host(host: &str) -> bool {
    !host.is_empty()
        && host.split('.').all(|label| {
            !label.is_empty() && label.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
        })
}

fn is_token_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b'.' | b'%')
}

/// Longest literal token used by callers that want to build an index.
pub fn extract_token(pattern: &str) -> Option<String> {
    let bytes = pattern.as_bytes();
    let mut best: Option<&[u8]> = None;
    let mut start = 0;
    while start < bytes.len() {
        while start < bytes.len() && !is_token_byte(bytes[start]) {
            start += 1;
        }
        let mut end = start;
        while end < bytes.len() && is_token_byte(bytes[end]) {
            end += 1;
        }
        if end - start >= 3
            && best
                .map(|current| end - start > current.len())
                .unwrap_or(true)
        {
            best = Some(&bytes[start..end]);
        }
        start = end;
    }
    best.and_then(|slice| std::str::from_utf8(slice).ok().map(ToOwned::to_owned))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reference(pat: &[u8], url: &[u8], end: bool) -> bool {
        match pat.split_first() {
            None => !end || url.is_empty(),
            Some((b'*', rest)) => (0..=url.len()).any(|n| reference(rest, &url[n..], end)),
            Some((b'^', rest)) if url.is_empty() => reference(rest, url, end),
            Some((b'^', rest)) => is_separator(url[0]) && reference(rest, &url[1..], end),
            Some((literal, rest)) => {
                url.first() == Some(literal) && reference(rest, &url[1..], end)
            }
        }
    }

    fn words(alphabet: &[char], depth: usize) -> Vec<String> {
        let mut all = vec![String::new()];
        let mut level = vec![String::new()];
        for _ in 0..depth {
            level = level
                .iter()
                .flat_map(|word| alphabet.iter().map(move |letter| format!("{word}{letter}")))
                .collect();
            all.extend(level.clone());
        }
        all
    }

    #[test]
    fn wildcard_pass_agrees_with_exhaustive_reference() {
        let urls = words(&['a', 'b', '/', '.'], 4);
        for pattern in words(&['a', 'b', '*', '^'], 4) {
            for url in &urls {
                for end in [false, true] {
                    assert_eq!(
                        pattern_matches(&pattern, Anchor::Start, end, url),
                        reference(pattern.as_bytes(), url.as_bytes(), end),
                        "start {pattern:?} {url:?} {end}"
                    );
                    let anywhere = (0..=url.len())
                        .any(|start| reference(pattern.as_bytes(), &url.as_bytes()[start..], end));
                    assert_eq!(
                        pattern_matches(&pattern, Anchor::None, end, url),
                        anywhere,
                        "anywhere {pattern:?} {url:?} {end}"
                    );
                }
            }
        }
    }

    #[test]
    fn token_and_domain_indices_agree_with_a_linear_rule_scan() {
        let list = "||example.com^\n||EXAMPLE.com/Case$match-case\n||example.com:8443/ads\n||ad*.test^\n/track*pixel^\nABCdef$match-case\nabc%20def\n*/a*b*c^\n/a^\n@@/allowed*pixel\n@@||safe.example.com^\n@@ABCdef$match-case\n/track$domain=page.test\n";
        let rules: Vec<_> = list
            .lines()
            .filter_map(|line| Rule::parse(line).ok())
            .collect();
        let mut matcher = Matcher::new();
        matcher.load(list);
        for host in [
            "example.com",
            "safe.example.com",
            "EXAMPLE.com",
            "example.com:8443",
            "notexample.com",
            "ads.test",
            "other.test",
        ] {
            for path in [
                "/",
                "/Case",
                "/case",
                "/ads",
                "/prefixABCdefsuffix",
                "/prefixabcdefsuffix",
                "/abc%20def",
                "/tracking-pixel/",
                "/allowed-track-pixel/",
                "/a/b/c/",
                "/a",
                "/track",
            ] {
                let url = format!("https://{host}{path}");
                let lower = url.to_lowercase();
                for resource in [
                    ResourceType::Script,
                    ResourceType::Image,
                    ResourceType::Media,
                ] {
                    for page in ["https://page.test/", "https://other.test/", ""] {
                        let first_party = extract_host(page).unwrap_or("");
                        let host = extract_host(&lower).unwrap_or("");
                        let third =
                            !first_party.is_empty() && !site_identity::same_site(host, first_party);
                        let matches = |rule: &&Rule| {
                            rule.options_apply(resource, first_party, third)
                                && pattern_matches(
                                    &rule.pattern,
                                    rule.anchor,
                                    rule.anchor_end,
                                    if rule.options.match_case {
                                        &url
                                    } else {
                                        &lower
                                    },
                                )
                        };
                        let expected = rules
                            .iter()
                            .filter(|r| !r.is_exception)
                            .any(|r| matches(&r))
                            && !rules.iter().filter(|r| r.is_exception).any(|r| matches(&r));
                        assert_eq!(
                            matcher.should_block(&url, page, resource),
                            expected,
                            "{url} {page}"
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn case_sensitive_domain_rules_remain_reachable() {
        let mut matcher = Matcher::new();
        matcher.load("||EXAMPLE.com/Case$match-case");
        assert!(matcher.should_block("https://EXAMPLE.com/Case", "", ResourceType::Script));
        assert!(!matcher.should_block("https://EXAMPLE.com/case", "", ResourceType::Script));
    }
}
