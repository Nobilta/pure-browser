use adblock::engine::Engine;
use adblock::matcher::extract_token;
use adblock::rule::{Anchor, ResourceType, Rule, SkipReason};

fn engine(rules: &[&str]) -> Engine {
    let mut e = Engine::new();
    e.add_list(&rules.join("\n"));
    e
}

const DOC: &str = "https://news.example.org/index.html";

#[test]
fn domain_anchor_matches_subdomain_but_not_suffix_collision() {
    let e = engine(&["||ads.com^"]);
    assert!(e.should_block("https://ads.com/a.js", DOC, ResourceType::Script));
    assert!(e.should_block("https://x.ads.com/a.js", DOC, ResourceType::Script));
    // The point of anchoring at a label boundary: this must NOT match.
    assert!(!e.should_block("https://notads.com/a.js", DOC, ResourceType::Script));
    assert!(!e.should_block("https://ads.com.evil.net/a.js", DOC, ResourceType::Script));
}

#[test]
fn separator_matches_end_of_url() {
    // `^` has to accept end-of-string, or `||ads.com^` misses `https://ads.com`.
    let e = engine(&["||ads.com^"]);
    assert!(e.should_block("https://ads.com", DOC, ResourceType::Image));
}

#[test]
fn separator_does_not_match_letter() {
    let e = engine(&["||track^"]);
    assert!(!e.should_block("https://tracker.com/x", DOC, ResourceType::Image));
}

#[test]
fn wildcard_spans_path_segments() {
    let e = engine(&["||cdn.net/*/banner.png"]);
    assert!(e.should_block("https://cdn.net/a/b/c/banner.png", DOC, ResourceType::Image));
    assert!(!e.should_block("https://cdn.net/a/b/c/logo.png", DOC, ResourceType::Image));
}

#[test]
fn trailing_pipe_requires_end_of_url() {
    let e = engine(&["||host.com/track|"]);
    assert!(e.should_block("https://host.com/track", DOC, ResourceType::Ping));
    assert!(!e.should_block("https://host.com/tracker", DOC, ResourceType::Ping));
    assert!(!e.should_block("https://host.com/track?x=1", DOC, ResourceType::Ping));
}

#[test]
fn exception_overrides_block() {
    let e = engine(&["||ads.com^", "@@||ads.com/allowed.js"]);
    assert!(e.should_block("https://ads.com/evil.js", DOC, ResourceType::Script));
    assert!(!e.should_block("https://ads.com/allowed.js", DOC, ResourceType::Script));
}

#[test]
fn resource_type_option_restricts_rule() {
    let e = engine(&["||cdn.net/x$script"]);
    assert!(e.should_block("https://cdn.net/x", DOC, ResourceType::Script));
    assert!(!e.should_block("https://cdn.net/x", DOC, ResourceType::Image));
}

#[test]
fn negated_resource_type_excludes() {
    let e = engine(&["||cdn.net/x$~image"]);
    assert!(e.should_block("https://cdn.net/x", DOC, ResourceType::Script));
    assert!(!e.should_block("https://cdn.net/x", DOC, ResourceType::Image));
}

#[test]
fn third_party_option_uses_document_host() {
    let e = engine(&["||cdn.net/p$third-party"]);
    assert!(e.should_block("https://cdn.net/p", DOC, ResourceType::Script));
    // Same-origin request: the rule must not fire.
    assert!(!e.should_block(
        "https://cdn.net/p",
        "https://cdn.net/page.html",
        ResourceType::Script
    ));
}

#[test]
fn subdomain_of_document_is_first_party() {
    let e = engine(&["||example.org/p$third-party"]);
    assert!(!e.should_block(
        "https://static.example.org/p",
        "https://example.org/page",
        ResourceType::Script
    ));
}

#[test]
fn empty_document_url_counts_as_first_party() {
    // Top-level navigation has no referring document; a $third-party rule must not block it.
    let e = engine(&["||example.org^$third-party"]);
    assert!(!e.should_block("https://example.org/", "", ResourceType::Document));
}

#[test]
fn domain_option_scopes_rule() {
    let e = engine(&["/promo.js$domain=example.org"]);
    assert!(e.should_block("https://cdn.net/promo.js", DOC, ResourceType::Script));
    assert!(!e.should_block(
        "https://cdn.net/promo.js",
        "https://other.com/page",
        ResourceType::Script
    ));
}

#[test]
fn uppercase_document_host_keeps_domain_options_working() {
    let e = engine(&["/promo.js$domain=example.org"]);
    assert!(e.should_block(
        "https://cdn.net/promo.js",
        "HTTPS://EXAMPLE.ORG/page",
        ResourceType::Script
    ));
}

#[test]
fn excluded_domain_option_scopes_rule() {
    let e = engine(&["/promo.js$domain=~example.org"]);
    assert!(!e.should_block("https://cdn.net/promo.js", DOC, ResourceType::Script));
    assert!(e.should_block(
        "https://cdn.net/promo.js",
        "https://other.com/page",
        ResourceType::Script
    ));
}

#[test]
fn non_http_schemes_are_never_blocked() {
    let e = engine(&["||ads.com^", "*"]);
    for url in [
        "data:image/png;base64,AAAA",
        "about:blank",
        "blob:https://x/y",
        "file:///a",
    ] {
        assert!(!e.should_block(url, DOC, ResourceType::Image), "{url}");
    }
}

#[test]
fn matching_is_case_insensitive_by_default() {
    let e = engine(&["||ads.com/track"]);
    assert!(e.should_block("https://ADS.com/TRACK", DOC, ResourceType::Image));
}

#[test]
fn match_case_option_is_respected() {
    let e = engine(&["||ads.com/Track$match-case"]);
    assert!(!e.should_block("https://ads.com/track", DOC, ResourceType::Image));
}

#[test]
fn port_in_url_does_not_defeat_host_comparison() {
    // Host extraction strips the port, so third-party attribution stays correct.
    let e = engine(&["||cdn.net/p$third-party"]);
    assert!(!e.should_block(
        "https://cdn.net:8443/p",
        "https://cdn.net/page",
        ResourceType::Script
    ));
}

#[test]
fn cosmetic_and_unsupported_lines_are_counted_not_loaded() {
    let mut e = Engine::new();
    e.add_list(
        "! comment\n\
         [Adblock Plus 2.0]\n\
         \n\
         example.com##.ad-banner\n\
         ##.global-ad\n\
         ||real.com/block\n\
         ||x.com^$csp=script-src none\n",
    );
    let s = e.stats();
    assert_eq!(s.network_rules, 1);
    assert_eq!(s.cosmetic_skipped, 2);
    assert_eq!(s.unsupported_skipped, 1);
    assert_eq!(e.rule_count(), 1);
}

#[test]
fn cosmetic_rule_with_dollar_in_selector_is_not_misparsed() {
    // Cosmetic detection must run before `$` option splitting.
    assert_eq!(
        Rule::parse("example.com##div[data-x=\"$\"]"),
        Err(SkipReason::Cosmetic)
    );
}

#[test]
fn unknown_option_skips_rule_rather_than_overblocking() {
    assert_eq!(
        Rule::parse("||x.com^$notarealoption"),
        Err(SkipReason::Unsupported)
    );
}

#[test]
fn regex_literal_rules_are_unsupported() {
    assert_eq!(Rule::parse("/ads?[0-9]+/"), Err(SkipReason::Unsupported));
}

#[test]
fn parses_anchors() {
    let r = Rule::parse("||a.com/x").unwrap();
    assert_eq!(r.anchor, Anchor::Domain);
    assert!(!r.anchor_end);
    assert!(!r.is_exception);

    let r = Rule::parse("|https://a.com").unwrap();
    assert_eq!(r.anchor, Anchor::Start);

    let r = Rule::parse("@@/x/y|").unwrap();
    assert_eq!(r.anchor, Anchor::None);
    assert!(r.anchor_end);
    assert!(r.is_exception);
}

#[test]
fn token_extraction_picks_longest_literal_run() {
    // Tokens may only contain bytes the URL scan also emits, so `/` never appears in one:
    // "banner.png" (10) beats "cdn.net" (7).
    assert_eq!(
        extract_token("||cdn.net/*/banner.png"),
        Some("banner.png".to_string())
    );
    assert_eq!(extract_token("*/ads/*"), Some("ads".to_string()));
    // Nothing with a 3+ byte run of token characters.
    assert_eq!(extract_token("*"), None);
    assert_eq!(extract_token("^^"), None);
    assert_eq!(extract_token("/a/b/"), None);
}

#[test]
fn untokenized_rule_still_matches() {
    // A rule with no index token must fall through to the linear scan, not be dropped.
    let e = engine(&["a^b"]);
    assert_eq!(e.rule_count(), 1);
    assert!(e.should_block("https://x.com/a/b", DOC, ResourceType::Other));
}

#[test]
fn malformed_urls_do_not_panic() {
    let e = engine(&["||ads.com^"]);
    for url in ["http:", "https://", "http://", "https:///", "https://:80"] {
        let _ = e.should_block(url, DOC, ResourceType::Other);
    }
}

#[test]
fn domain_anchor_accepts_case_insensitive_http_scheme() {
    let e = engine(&["||ads.example^"]);
    assert!(e.should_block("HTTPS://ADS.EXAMPLE/script.js", DOC, ResourceType::Script));
}

#[test]
fn wildcard_matching_handles_adversarial_star_patterns() {
    // This used to recurse over every possible expansion of each `*`, making a hostile
    // custom rule capable of exhausting the renderer thread. The iterative matcher should
    // finish quickly while preserving the expected result.
    let pattern = format!("{}z", "*a".repeat(32));
    let mut e = Engine::new();
    e.add_list(&pattern);
    assert!(!e.should_block(
        &format!("https://example.com/{}y", "a".repeat(256)),
        DOC,
        ResourceType::Other
    ));
}

#[test]
fn wildcard_after_domain_prefix_is_not_lost_from_the_index() {
    // The host prefix contains a wildcard, so it must use the fallback matcher rather than
    // being stored under a key that the domain-suffix probe can never visit.
    let e = engine(&["||example.com*tracking"]);
    assert!(e.should_block(
        "https://example.com/path/tracking.gif",
        DOC,
        ResourceType::Image
    ));
}

#[test]
fn oversized_rule_is_skipped_without_being_retained() {
    let mut e = Engine::new();
    e.add_list(&format!("||example.com/{}", "x".repeat(20_000)));
    assert_eq!(e.rule_count(), 0);
    assert_eq!(e.stats().unsupported_skipped, 1);
}
