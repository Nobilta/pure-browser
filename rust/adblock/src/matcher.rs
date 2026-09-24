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
    fallback_bytes: usize,
    count: usize,
    /// Length of the longest `domains` key, so the suffix probe can stop early.
    max_domain_key: usize,
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

/// Why a rule could not enter a rule set.
#[derive(Debug, PartialEq, Eq)]
enum Refusal {
    /// A capacity cap refused it. Kept apart from unsupported syntax because the two need
    /// different answers: a truncated subscription is fixed by trimming lists, not by finding a
    /// rule the engine cannot express.
    Limit,
    /// Nothing in the pattern can be looked up by; no valid rule reaches this.
    Unplaceable,
}

impl RuleSet {
    /// Adds a rule, or reports why it could not be placed, so the caller can count the shortfall
    /// instead of silently dropping filters.
    fn add(&mut self, rule: Rule) -> Result<(), Refusal> {
        if self.count >= MAX_RULES {
            return Err(Refusal::Limit);
        }
        if rule.anchor == Anchor::Domain {
            let host = rule
                .pattern
                .split(['/', '^'])
                .next()
                .unwrap_or(&rule.pattern);
            if is_indexable_domain_host(host) {
                // Case-sensitive paths still need a lowercase key for the host lookup.
                let key = host.to_ascii_lowercase();
                self.max_domain_key = self.max_domain_key.max(key.len());
                self.domains.entry(key).or_default().push(rule);
                self.count += 1;
                return Ok(());
            }
        }
        if let Some(literal) = extract_token(&rule.pattern) {
            // Pick the least populated triplet, avoiding common prefixes such as "http".
            // Every triplet in this literal is necessary, including when it occurs inside
            // a longer URL word. Splitting the URL into words would miss such matches.
            let rarest = literal
                .as_bytes()
                .windows(3)
                .map(|bytes| {
                    [
                        bytes[0].to_ascii_lowercase(),
                        bytes[1].to_ascii_lowercase(),
                        bytes[2].to_ascii_lowercase(),
                    ]
                })
                .min_by_key(|key| self.tokens.get(key).map_or(0, Vec::len));
            if let Some(key) = rarest {
                self.tokens
                    .entry(key)
                    .or_default()
                    .push(TokenRule { rule, literal });
                self.count += 1;
                return Ok(());
            }
            // No triplet means nothing to look the rule up by. The engine is loaded inside
            // the app process, so an impossible invariant must not take the process down;
            // tests still fail loudly if token extraction ever starts returning short
            // literals.
            debug_assert!(false, "extracted tokens have at least three bytes");
            return Err(Refusal::Unplaceable);
        }
        if self.fallback.len() >= MAX_FALLBACK_RULES
            || self.fallback_bytes + rule.pattern.len() > MAX_FALLBACK_PATTERN_BYTES
        {
            return Err(Refusal::Limit);
        }
        self.fallback_bytes += rule.pattern.len();
        self.fallback.push(rule);
        self.count += 1;
        Ok(())
    }

    fn matches(&self, request: &Request<'_>) -> bool {
        let host = request.host;
        // No key in the map is longer than `max_domain_key`, so a longer suffix cannot be
        // present. Walking the suffixes shortest first and stopping at that length keeps the
        // probe linear; hashing every suffix of a host with thousands of labels costs
        // quadratic time in the host length, which a page-chosen URL can drive.
        for offset in host
            .match_indices('.')
            .map(|(offset, _)| offset + 1)
            .rev()
            .chain(std::iter::once(0))
        {
            let suffix = &host[offset..];
            if suffix.len() > self.max_domain_key {
                break;
            }
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
        // Every rule in this bucket is scanned for each request, which is why the bucket is
        // bounded when the list is loaded rather than while a request is in flight: a
        // request-time limit would depend on which rules happened to load first and would stop
        // blocking without saying so.
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
    /// Rules refused because a rule set was full. Separate from [Self::skipped_unsupported]: this
    /// one means the list was longer than the engine holds, which trimming subscriptions fixes.
    pub refused_by_limit: usize,
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
                Ok(rule) if rule.is_exception => match self.exceptions.add(rule) {
                    Ok(()) => stats.exceptions += 1,
                    Err(Refusal::Limit) => stats.refused_by_limit += 1,
                    Err(Refusal::Unplaceable) => stats.skipped_unsupported += 1,
                },
                Ok(rule) => match self.blocking.add(rule) {
                    Ok(()) => stats.blocking_rules += 1,
                    Err(Refusal::Limit) => stats.refused_by_limit += 1,
                    Err(Refusal::Unplaceable) => stats.skipped_unsupported += 1,
                },
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
        // A reference scan over the list text, not a query of a loaded engine: this call takes no
        // handle, so it cannot know what a cap refused. Callers pair the answer with
        // `LoadStats::refused_by_limit` and say so when that count is not zero — a rule the engine
        // dropped must never be presented as one that should have blocked.
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
/// `^` means "an ASCII byte that is neither a letter, a digit, nor one of `.`, `-`, `_`, `%`" —
/// typically `/`, `?`, `&`, `:` — so it anchors at boundaries without naming the exact character.
/// `%` is excluded because Adblock Plus does not treat it as a separator: a percent-encoded path
/// is still one path segment. A byte of a multi-byte character is not a separator either; the
/// class stops at `\x7F`.
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
            // Both passes run once over the whole URL and the loop only reads their rows.
            // Running either program inside the loop multiplied its cost by the number of
            // label boundaries, and a page chooses its host: `||google.*/pagead/lvz?` against
            // a 4,000-label host cost ~90 ms, and a host wildcard that satisfies the guard at
            // every boundary (say `||a.*` against `a.a.a…`) left the full-pattern pass
            // quadratic even after the guard itself became a single pass.
            let host_row = rule_host
                .contains('*')
                .then(|| wildcard_row(rule_host.as_bytes(), host.as_bytes(), true));
            let pattern_row = pat
                .contains(&b'*')
                .then(|| wildcard_row(pat, bytes, anchor_end));
            // A host wildcard that continues into the path (`||example.com*tracking`) cannot
            // be held to the whole-host comparison the row performs, because the wildcard is
            // meant to span the path. For those, only the literal prefix of the host part has
            // to match the request host exactly. The length test keeps this to patterns with
            // no path or separator of their own, which is where the form occurs.
            let wildcard_host_prefix = rule_host
                .split_once('*')
                .filter(|_| rule_authority.len() == pattern.len())
                .map(|(prefix, _)| prefix)
                .filter(|prefix| !prefix.is_empty());
            for pos in std::iter::once(0).chain(
                host.bytes()
                    .enumerate()
                    .filter_map(|(i, b)| (b == b'.').then_some(i + 1)),
            ) {
                let remaining = &host[pos..];
                let host_matches = match &host_row {
                    Some(row) => row[pos],
                    None => remaining == rule_host,
                } || wildcard_host_prefix
                    .is_some_and(|prefix| remaining == prefix);
                if !host_matches {
                    continue;
                }
                let start = authority_offset + host_offset + pos;
                let matched = match &pattern_row {
                    Some(row) => row.get(start).copied().unwrap_or(false),
                    None => wildcard_at(pat, bytes, start, anchor_end),
                };
                if matched {
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

/// Adblock Plus compiles `^` to the character class
/// `/[\x00-\x24\x26-\x2C\x2F\x3A-\x40\x5B-\x5E\x60\x7B-\x7F]/` — every ASCII byte except a
/// letter, a digit, `_`, `.`, `%` and `-`. Two details matter and both were wrong here: leaving
/// `%` out made `^` match inside percent-encoded paths, so `/ads^` blocked `/ads%20foo`; and the
/// class stops at `\x7F`, so a byte of a multi-byte character is not a separator either.
fn is_separator(byte: u8) -> bool {
    byte.is_ascii() && !byte.is_ascii_alphanumeric() && !matches!(byte, b'.' | b'-' | b'_' | b'%')
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
    let row = wildcard_row(pat, url, anchor_end);
    if anywhere {
        row.into_iter().any(|matched| matched)
    } else {
        row[0]
    }
}

/// The full row behind [`wildcard_match`]: `row[i]` is true when `pat` matches `url[i..]`,
/// consuming all of it when `anchor_end`, or any prefix of it otherwise. Callers that need
/// the answer for many start offsets read the row once instead of re-running the program
/// per offset.
fn wildcard_row(pat: &[u8], url: &[u8], anchor_end: bool) -> Vec<bool> {
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
    row
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

/// Rules one rule set may hold. The byte caps live on the Kotlin side, but they bound the
/// list text, not the rule count, and several subscriptions share one engine — so the
/// engine bounds its own memory here. Loads past the cap report the shortfall as skipped
/// instead of growing without limit.
const MAX_RULES: usize = 200_000;

/// Rules the token and domain indices cannot place are scanned for every request, so the
/// bucket is capped. The three shipped lists leave thirteen rules here.
const MAX_FALLBACK_RULES: usize = 1_024;

/// Total pattern text the fallback bucket may hold.
///
/// This is what actually bounds the scan. A `^`-only pattern is retried at every URL offset,
/// so it costs pattern length x URL length, and a wildcard costs the same; bounding the bucket's
/// pattern bytes therefore bounds one request at roughly this value times the app's 8,192-byte
/// URL cap, about 17 million cells. The shipped lists need 104 bytes, so a legitimate list has
/// an order of magnitude of headroom and anything past the cap is reported through the load
/// statistics instead of quietly ceasing to block at request time.
const MAX_FALLBACK_PATTERN_BYTES: usize = 2 * 1024;

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
            // Adblock Plus' own character class, transcribed as ranges rather than derived
            // from a predicate, so that redefining `is_separator` cannot silently redefine what
            // this oracle expects. Sharing the definition is how the missing `%` stayed
            // invisible, and a shared predicate would equally hide a wrong one.
            Some((b'^', rest)) => {
                let byte = url[0];
                matches!(byte,
                    0x00..=0x24 | 0x26..=0x2C | 0x2F | 0x3A..=0x40 | 0x5B..=0x5E | 0x60 | 0x7B..=0x7F)
                    && reference(rest, &url[1..], end)
            }
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

    /// The fallback bucket is scanned for every request, so it is bounded when a list loads.
    /// These assertions are the calibration: if a change makes the shipped lists approach
    /// either cap, the next list to load would start losing rules.
    #[test]
    fn shipped_lists_stay_well_inside_the_fallback_caps() {
        let mut matcher = Matcher::new();
        for list in [
            include_str!("../../../app/src/main/assets/filters/easylist.txt"),
            include_str!("../../../app/src/main/assets/filters/easyprivacy.txt"),
            include_str!("../../../app/src/main/assets/filters/easylist-china.txt"),
        ] {
            matcher.load(list);
        }
        let fallback = &matcher.blocking.fallback;
        assert!(
            !fallback.is_empty(),
            "the shipped lists must reach the fallback bucket"
        );
        assert!(
            fallback.len() * 8 < MAX_FALLBACK_RULES,
            "shipped fallback rules: {} of {MAX_FALLBACK_RULES}",
            fallback.len()
        );
        assert!(
            matcher.blocking.fallback_bytes * 8 < MAX_FALLBACK_PATTERN_BYTES,
            "shipped fallback bytes: {} of {MAX_FALLBACK_PATTERN_BYTES}",
            matcher.blocking.fallback_bytes
        );
    }

    /// Anything past a cap is refused while loading and counted, rather than kept and then
    /// skipped for some requests depending on the order the lists happened to load in.
    ///
    /// The count lands in its own field: these rules are refused because the list is longer than
    /// the engine holds, which is a subscription the user can trim, and reporting them as
    /// unsupported syntax would send a bug report looking for a rule the engine cannot express.
    #[test]
    fn fallback_rules_past_a_cap_are_refused_at_load_and_counted_apart_from_syntax() {
        let list: String = (0..500).map(|_| format!("{}\n", "^".repeat(64))).collect();
        let mut matcher = Matcher::new();
        let stats = matcher.load(&list);
        assert!(
            matcher.blocking.fallback_bytes <= MAX_FALLBACK_PATTERN_BYTES,
            "the byte cap must hold: {}",
            matcher.blocking.fallback_bytes
        );
        assert!(stats.refused_by_limit > 0, "the refusal must be reported");
        assert_eq!(
            stats.skipped_unsupported, 0,
            "a capacity refusal is not unsupported syntax"
        );
        assert!(matcher.rule_count() < 500, "refused rules are not retained");
    }

    #[test]
    fn wildcard_pass_agrees_with_exhaustive_reference() {
        let urls = words(&['a', 'b', '/', '.', '%'], 3);
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

    /// The exhaustive test above only reaches `Anchor::Start` and `Anchor::None`, so nothing
    /// pinned the domain guard's row read — swapping it for a prefix match, or only consulting
    /// position zero, left the whole suite green. This compares the branch against a reference
    /// that walks the label boundaries by hand.
    #[test]
    fn domain_anchor_agrees_with_a_per_boundary_reference() {
        fn reference_domain(pattern: &str, url: &str) -> bool {
            let rest = url
                .strip_prefix("http://")
                .or_else(|| url.strip_prefix("https://"));
            let Some(after_scheme) = rest else {
                return false;
            };
            let authority_end = after_scheme
                .find(['/', '?', '#'])
                .unwrap_or(after_scheme.len());
            let authority = &after_scheme[..authority_end];
            let host_port = authority.rsplit('@').next().unwrap_or(authority);
            let host = host_port
                .strip_prefix('[')
                .and_then(|value| value.find(']').map(|end| &host_port[..end + 2]))
                .unwrap_or_else(|| host_port.split(':').next().unwrap_or(host_port));
            let rule_authority = pattern
                .split(['/', '^'])
                .next()
                .unwrap_or(pattern)
                .trim_end_matches('|');
            let rule_host = rule_authority
                .strip_prefix('[')
                .and_then(|value| value.find(']').map(|end| &rule_authority[..end + 2]))
                .unwrap_or_else(|| rule_authority.split(':').next().unwrap_or(rule_authority));
            let scheme_len = url.len() - after_scheme.len();
            let host_offset = authority.len() - host_port.len();
            // The boundaries the anchor allows. The matching below is deliberately brute force
            // — the host guard is tried against every prefix length instead of being read out
            // of a dynamic-programming row — because that read is what this test has to pin.
            for pos in std::iter::once(0).chain(
                host.bytes()
                    .enumerate()
                    .filter_map(|(i, b)| (b == b'.').then_some(i + 1)),
            ) {
                let remaining = &host[pos..];
                let host_matches = (0..=remaining.len())
                    .any(|end| reference(rule_host.as_bytes(), &remaining.as_bytes()[..end], true))
                    || (rule_authority.len() == pattern.len()
                        && rule_host
                            .split_once('*')
                            .is_some_and(|(prefix, _)| !prefix.is_empty() && remaining == prefix));
                if host_matches
                    && reference(
                        pattern.as_bytes(),
                        &url.as_bytes()[scheme_len + host_offset + pos..],
                        false,
                    )
                {
                    return true;
                }
            }
            false
        }

        let hosts = [
            "example.com",
            "a.example.com",
            "example.com.evil.net",
            "a.a.a.example.com",
            "example.com.",
            "a..b.com",
            "adx.example.com",
            "example.comm",
            // A wildcard host followed by a path, reached at a label boundary past zero: this is
            // the only shape where the guard's per-position answer is load bearing, so it is
            // what pins the row read against "consult position zero only".
            "adx.com",
            "sub.adx.com",
            "deep.sub.adx.com",
        ];
        let patterns = [
            "example.com",
            "example.com^",
            "example.com/ads",
            "example.com*tracking",
            "example.com*",
            "*example.com^",
            "example.com:8443/ads",
            "ad*.com^",
            "example.comm^",
        ];
        for pattern in patterns {
            // Through the parser, so the pattern under test is the one the engine would hold;
            // the plain-host rewrite that `parse` applies is covered by the ABP cases above.
            let rule = Rule::parse(&format!("||{pattern}")).expect("test pattern must parse");
            assert_eq!(rule.anchor, Anchor::Domain);
            for host in hosts {
                let url = format!("https://{host}/ads/tracking.gif");
                assert_eq!(
                    pattern_matches(&rule.pattern, rule.anchor, rule.anchor_end, &url),
                    reference_domain(&rule.pattern, &url),
                    "domain {:?} (from {pattern:?}) {url:?}",
                    rule.pattern
                );
            }
        }
    }

    /// The separator class stops at `\x7F`, so a byte of a multi-byte character is not one.
    #[test]
    fn a_non_ascii_byte_is_not_a_separator() {
        let mut e = crate::engine::Engine::new();
        e.add_list("/ads^");
        assert!(
            !e.should_block(
                "https://x.example/ads網",
                "https://x.example/",
                ResourceType::Image
            ),
            "`^` must not consume a multi-byte character"
        );
        assert!(e.should_block(
            "https://x.example/ads/",
            "https://x.example/",
            ResourceType::Image
        ));
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
