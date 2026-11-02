//! Public, testable facade for the network-rule matcher.
//!
//! The JNI path uses [`crate::matcher::Matcher`] directly. This small facade preserves a
//! convenient API for Rust callers and keeps load statistics cumulative when several lists
//! are added.

use crate::cosmetic::CosmeticMatcher;
use crate::matcher::Matcher;
use crate::rule::{Anchor, ResourceType, Rule, RuleOptions, SkipReason};

#[derive(Default, Debug, Clone, PartialEq, Eq)]
pub struct LoadStats {
    pub total_lines: usize,
    pub network_rules: usize,
    pub cosmetic_skipped: usize,
    pub unsupported_skipped: usize,
    /// Rules dropped because a rule set was full; see `matcher::LoadStats::refused_by_limit`.
    pub refused_by_limit: usize,
    pub skipped_blank: usize,
    pub skipped_comment: usize,
}

pub struct Engine {
    matcher: Matcher,
    stats: LoadStats,
}

impl Default for Engine {
    fn default() -> Self {
        Self::new()
    }
}

impl Engine {
    pub fn new() -> Self {
        Self {
            matcher: Matcher::new(),
            stats: LoadStats::default(),
        }
    }

    /// Parse and append one or more EasyList lists.
    pub fn add_list(&mut self, content: &str) {
        let loaded = self.matcher.load(content);
        self.stats.total_lines += loaded.total_lines;
        self.stats.network_rules += loaded.blocking_rules + loaded.exceptions;
        self.stats.cosmetic_skipped += loaded.skipped_cosmetic;
        self.stats.unsupported_skipped += loaded.skipped_unsupported;
        self.stats.refused_by_limit += loaded.refused_by_limit;
        self.stats.skipped_blank += loaded.skipped_blank;
        self.stats.skipped_comment += loaded.skipped_comment;
    }

    pub fn stats(&self) -> &LoadStats {
        &self.stats
    }

    pub fn rule_count(&self) -> usize {
        self.matcher.rule_count()
    }

    pub fn should_block(&self, url: &str, document_url: &str, resource: ResourceType) -> bool {
        self.matcher.should_block(url, document_url, resource)
    }

    pub fn page_policy(&self, document_url: &str) -> crate::matcher::PagePolicy {
        self.matcher.page_policy(document_url)
    }

    /// Exposes the parser's skip classification for callers that want to inspect a line.
    pub fn parse_line(line: &str) -> Result<crate::rule::Rule, SkipReason> {
        crate::rule::Rule::parse(line)
    }
}

/// Counts what the real loaders accept, using a temporary matcher and cosmetic matcher.
/// This keeps validation aligned with `MATCHER::load` (16 KiB line cap, capacity refusals,
/// cosmetic selector validation) instead of counting parser successes that never load.
pub fn list_stats(content: &str) -> Option<(usize, usize)> {
    // Feed the actual loaders, but drop prose that the parser can represent as an
    // unanchored wildcard (`Forbidden`, `Service Unavailable`) and would otherwise count.
    let filtered: String = content
        .lines()
        .filter(|line| match Rule::parse(line) {
            Ok(rule) => is_usable_network(&rule),
            Err(_) => true,
        })
        .collect::<Vec<_>>()
        .join("\n");
    let mut network = Matcher::new();
    let loaded = network.load(&filtered);
    let mut cosmetic = CosmeticMatcher::new();
    cosmetic.load(&filtered);
    let network_count = loaded.blocking_rules + loaded.exceptions;
    let cosmetic_count = cosmetic.rule_count();
    (network_count + cosmetic_count > 0).then_some((network_count, cosmetic_count))
}

fn is_usable_network(rule: &Rule) -> bool {
    !rule.pattern.chars().any(char::is_whitespace)
        && (rule.options != RuleOptions::default()
            || (rule.pattern.chars().any(char::is_alphanumeric)
                && (rule.anchor != Anchor::None
                    || rule.anchor_end
                    || rule.pattern.contains('.')
                    || rule.pattern.contains('/'))))
}
