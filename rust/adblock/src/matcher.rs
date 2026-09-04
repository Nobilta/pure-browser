//! The matcher that holds all loaded rules and answers "should this request block?".
//!
//! Two indices: a domain-anchor hash for `||example.com/*` patterns (the common case), and
//! a linear scan fallback for everything else. The domain index turns 50k rules into ~200
//! probes; the fallback is a SIMD-friendly wildcard walk that still runs in microseconds
//! for the ~5k rules that land there.

use crate::rule::{domain_matches, Anchor, ResourceType, Rule, SkipReason};
use std::collections::HashMap;

pub struct Matcher {
    /// Rules keyed by their domain anchor's host part, e.g. `||ads.example.com/x` -> "ads.example.com".
    domain_rules: HashMap<String, Vec<Rule>>,
    /// Everything that doesn't have a clean domain anchor.
    fallback_rules: Vec<Rule>,
    /// `@@` exceptions, checked after a blocking match to see if it's explicitly allowed.
    exceptions: Vec<Rule>,
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
        Matcher {
            domain_rules: HashMap::new(),
            fallback_rules: Vec::new(),
            exceptions: Vec::new(),
        }
    }

    /// Parse and load rules from raw text (one rule per line).
    ///
    /// Appends to existing rules rather than replacing, so multiple lists can be loaded.
    pub fn load(&mut self, content: &str) -> LoadStats {
        let mut stats = LoadStats::default();
        for line in content.lines() {
            stats.total_lines += 1;
            // A malformed custom list must not turn one request into an unbounded parser
            // allocation. Normal EasyList rules are far smaller than this; oversized lines
            // are reported as unsupported and the rest of the list remains usable.
            if line.len() > MAX_RULE_LENGTH {
                stats.skipped_unsupported += 1;
                continue;
            }
            match Rule::parse(line) {
                Ok(rule) => {
                    if rule.is_exception {
                        stats.exceptions += 1;
                        self.exceptions.push(rule);
                    } else {
                        stats.blocking_rules += 1;
                        self.add_rule(rule);
                    }
                }
                Err(SkipReason::Blank) => stats.skipped_blank += 1,
                Err(SkipReason::Comment) => stats.skipped_comment += 1,
                Err(SkipReason::Cosmetic) => stats.skipped_cosmetic += 1,
                Err(SkipReason::Unsupported) => stats.skipped_unsupported += 1,
            }
        }
        stats
    }

    fn add_rule(&mut self, rule: Rule) {
        if rule.anchor == Anchor::Domain {
            // Extract the host part: everything up to the first `/` or `^`.
            let host = rule
                .pattern
                .split(&['/', '^'][..])
                .next()
                .unwrap_or(&rule.pattern)
                .to_string();
            // Only clean host prefixes are safe index keys. A wildcard, port, or other
            // syntax in this segment would make the key invisible to the suffix probe and
            // effectively drop an otherwise valid rule. Such rules are uncommon, so the
            // linear fallback is the correct trade-off for preserving semantics.
            if is_indexable_domain_host(&host) {
                self.domain_rules.entry(host).or_default().push(rule);
                return;
            }
        }
        self.fallback_rules.push(rule);
    }

    /// True if this request should be blocked.
    ///
    /// `url` is the full request URL; `first_party` is the page's top-level origin (can be
    /// empty if unknown). `resource_type` is the semantic type from the WebView callback.
    pub fn should_block(&self, url: &str, first_party: &str, resource_type: ResourceType) -> bool {
        if !is_http_url(url) {
            return false;
        }
        let url_lower = url.to_lowercase();
        // Rule domains are normalised to lowercase during parsing. Normalise the page
        // origin too, otherwise an uppercase Host in a WebView callback silently disables
        // every `$domain=` and third-party decision for that document.
        let first_party_lower = first_party.to_lowercase();
        let first_party_host = extract_host(&first_party_lower).unwrap_or("");
        let is_third_party = !first_party_host.is_empty() && !urls_same_domain(url, first_party);

        // Try the domain index first.
        if let Some(host) = extract_host(&url_lower) {
            // Probe the full host and each parent suffix. A `||example.com` rule applies
            // to `cdn.example.com` as well as the apex domain.
            let labels: Vec<&str> = host.trim_matches(['[', ']']).split('.').collect();
            for start in 0..labels.len() {
                let suffix = labels[start..].join(".");
                if let Some(candidates) = self.domain_rules.get(&suffix) {
                    for rule in candidates {
                        if self.rule_matches(
                            rule,
                            url,
                            &url_lower,
                            first_party_host,
                            resource_type,
                            is_third_party,
                        ) {
                            return !self.has_exception(
                                &url_lower,
                                url,
                                first_party_host,
                                resource_type,
                                is_third_party,
                            );
                        }
                    }
                }
            }
        }

        // Fall back to linear scan.
        for rule in &self.fallback_rules {
            if self.rule_matches(
                rule,
                url,
                &url_lower,
                first_party_host,
                resource_type,
                is_third_party,
            ) {
                return !self.has_exception(
                    &url_lower,
                    url,
                    first_party_host,
                    resource_type,
                    is_third_party,
                );
            }
        }

        false
    }

    fn rule_matches(
        &self,
        rule: &Rule,
        url_original: &str,
        url_lower: &str,
        first_party: &str,
        resource_type: ResourceType,
        is_third_party: bool,
    ) -> bool {
        if !rule.options_apply(resource_type, first_party, is_third_party) {
            return false;
        }
        let url = if rule.options.match_case {
            url_original
        } else {
            url_lower
        };
        pattern_matches(&rule.pattern, rule.anchor, rule.anchor_end, url)
    }

    fn has_exception(
        &self,
        url_lower: &str,
        url_original: &str,
        first_party: &str,
        resource_type: ResourceType,
        is_third_party: bool,
    ) -> bool {
        self.exceptions.iter().any(|exc| {
            if !exc.options_apply(resource_type, first_party, is_third_party) {
                return false;
            }
            let url = if exc.options.match_case {
                url_original
            } else {
                url_lower
            };
            pattern_matches(&exc.pattern, exc.anchor, exc.anchor_end, url)
        })
    }

    /// Total number of network rules loaded (blocking + exception).
    pub fn rule_count(&self) -> usize {
        let blocking: usize = self.domain_rules.values().map(|v| v.len()).sum();
        blocking + self.fallback_rules.len() + self.exceptions.len()
    }
}

impl Default for Matcher {
    fn default() -> Self {
        Self::new()
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
        Anchor::None => (0..=bytes.len()).any(|start| wildcard_at(pat, bytes, start, anchor_end)),
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
            let host = authority.rsplit('@').next().unwrap_or(authority);
            let host = host
                .strip_prefix('[')
                .and_then(|v| v.find(']').map(|end| &v[..end]))
                .unwrap_or_else(|| host.split(':').next().unwrap_or(host));
            let rule_host = pattern
                .split(['/', '^', '*'])
                .next()
                .unwrap_or(pattern)
                .trim_end_matches('|');
            if host != rule_host && !host.ends_with(&format!(".{rule_host}")) {
                return false;
            }
            // Find the rule host at a label boundary in the authority and match the
            // complete pattern from there. This prevents `example.com` from matching
            // `example.com.evil.test`.
            let authority_offset = url.len() - after_scheme.len();
            let mut search_from = 0;
            while let Some(relative) = authority[search_from..].find(rule_host) {
                let pos = search_from + relative;
                let boundary_before = pos == 0 || authority.as_bytes()[pos - 1] == b'.';
                let after = pos + rule_host.len();
                let boundary_after = after == authority.len()
                    || matches!(authority.as_bytes()[after], b':' | b'/' | b'?' | b'#');
                if boundary_before
                    && boundary_after
                    && wildcard_at(pat, bytes, authority_offset + pos, anchor_end)
                {
                    return true;
                }
                search_from = pos + 1;
                if search_from >= authority.len() {
                    break;
                }
            }
            false
        }
    }
}

/// Matches one pattern position without recursive backtracking.
///
/// The old implementation recursively tried every possible expansion of `*`. A rule such
/// as `*a*a*a*a*a*` against a long URL could therefore consume exponential CPU and stack
/// space when a page supplied a hostile custom list. This dynamic-programming walk has the
/// same EasyList semantics (including zero-width `^` at end-of-URL) with O(pattern × URL)
/// time and O(URL) memory, and never grows the native stack with input size.
fn wildcard_at(pat: &[u8], url: &[u8], start: usize, anchor_end: bool) -> bool {
    if start > url.len() {
        return false;
    }
    let haystack = &url[start..];
    let width = haystack.len();

    // `next[ui]` is the answer for the suffix beginning at pattern index `pi + 1`.
    // At the end of the pattern, an anchored rule must have consumed the whole URL.
    let mut next = vec![false; width + 1];
    next[width] = true;
    if !anchor_end {
        next.fill(true);
    }

    for &token in pat.iter().rev() {
        let mut current = vec![false; width + 1];
        match token {
            // Either let `*` consume nothing (the next row) or consume one byte and keep
            // the star active (the value already computed immediately to the right).
            b'*' => {
                for ui in (0..=width).rev() {
                    current[ui] = next[ui] || (ui < width && current[ui + 1]);
                }
            }
            // A separator is one non-token character, except at end-of-URL where it is
            // zero-width. This is the same boundary rule used by the recursive matcher.
            b'^' => {
                for ui in 0..=width {
                    current[ui] = if ui == width {
                        next[ui]
                    } else {
                        let byte = haystack[ui];
                        !byte.is_ascii_alphanumeric()
                            && byte != b'.'
                            && byte != b'-'
                            && byte != b'_'
                            && next[ui + 1]
                    };
                }
            }
            literal => {
                for ui in 0..width {
                    current[ui] = haystack[ui] == literal && next[ui + 1];
                }
            }
        }
        next = current;
    }
    next[0]
}

fn extract_host(url: &str) -> Option<&str> {
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

fn urls_same_domain(a: &str, b: &str) -> bool {
    let a_lower = a.to_lowercase();
    let b_lower = b.to_lowercase();
    let host_a = extract_host(&a_lower).unwrap_or("");
    let host_b = extract_host(&b_lower).unwrap_or("");
    if host_a == host_b {
        return true;
    }
    domain_matches(host_a, host_b) || domain_matches(host_b, host_a)
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
