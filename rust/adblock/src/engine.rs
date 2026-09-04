//! Public, testable facade for the network-rule matcher.
//!
//! The JNI path uses [`crate::matcher::Matcher`] directly. This small facade preserves a
//! convenient API for Rust callers and keeps load statistics cumulative when several lists
//! are added.

use crate::matcher::Matcher;
use crate::rule::{ResourceType, SkipReason};

#[derive(Default, Debug, Clone, PartialEq, Eq)]
pub struct LoadStats {
    pub total_lines: usize,
    pub network_rules: usize,
    pub cosmetic_skipped: usize,
    pub unsupported_skipped: usize,
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

    /// Exposes the parser's skip classification for callers that want to inspect a line.
    pub fn parse_line(line: &str) -> Result<crate::rule::Rule, SkipReason> {
        crate::rule::Rule::parse(line)
    }
}
