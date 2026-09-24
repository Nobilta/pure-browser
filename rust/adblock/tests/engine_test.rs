use adblock::engine::Engine;
use adblock::matcher::extract_token;
use adblock::rule::{Anchor, ResourceType, Rule, SkipReason};
use std::hint::black_box;

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
fn party_rules_use_registrable_domains_including_private_suffixes() {
    for (page, request, third_party) in [
        ("www.example.com", "cdn.example.com", false),
        ("www.example.com", "cdn.other.com", true),
        ("example.com", "cdn.example.com", false),
        ("www.example.co.uk", "cdn.example.co.uk", false),
        ("alice.github.io", "bob.github.io", true),
        ("a.b.ck", "x.b.ck", true),
        ("www.ck", "a.www.ck", false),
        ("www.食狮.com.cn", "cdn.xn--85x722f.com.cn", false),
        ("127.0.0.1", "127.0.0.2", true),
        ("localhost", "localhost", false),
    ] {
        for (option, expected) in [("third-party", third_party), ("~third-party", !third_party)] {
            let e = engine(&[&format!("/app.js${option}")]);
            assert_eq!(
                e.should_block(
                    &format!("https://{request}/app.js"),
                    &format!("https://{page}/"),
                    ResourceType::Script
                ),
                expected,
                "{page} -> {request} ${option}"
            );
        }
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

#[test]
fn domain_rules_preserve_ports_and_hostname_boundaries() {
    let e = engine(&[
        "||127.0.0.1:8875/filter-marker.js",
        "||media*.example.com^",
        "||[::1]:8875/tracker",
    ]);
    assert!(e.should_block(
        "http://127.0.0.1:8875/filter-marker.js",
        DOC,
        ResourceType::Script
    ));
    assert!(!e.should_block(
        "http://127.0.0.1:8876/filter-marker.js",
        DOC,
        ResourceType::Script
    ));
    assert!(!e.should_block(
        "http://127.0.0.1.evil.test:8875/filter-marker.js",
        DOC,
        ResourceType::Script
    ));
    assert!(e.should_block("https://media2.example.com/file", DOC, ResourceType::Other));
    assert!(!e.should_block(
        "https://media2.example.com.evil.test/file",
        DOC,
        ResourceType::Other
    ));
    assert!(e.should_block("http://[::1]:8875/tracker", DOC, ResourceType::Other));
    assert!(!e.should_block("http://[::1]:8876/tracker", DOC, ResourceType::Other));
}

#[test]
fn packaged_lists_and_local_subscription_work_together() {
    let mut e = Engine::new();
    e.add_list(include_str!(
        "../../../app/src/main/assets/filters/easylist.txt"
    ));
    e.add_list(include_str!(
        "../../../app/src/main/assets/filters/easyprivacy.txt"
    ));
    e.add_list(include_str!(
        "../../../app/src/main/assets/filters/easylist-china.txt"
    ));
    e.add_list("||127.0.0.1:8875/filter-marker.js");
    let page = "http://127.0.0.1:8875/feature-fixture.html";
    assert!(e.should_block(
        "http://127.0.0.1:8875/filter-marker.js",
        page,
        ResourceType::Script
    ));
    assert!(!e.should_block(
        "http://127.0.0.1:8875/feature-dependency.js",
        page,
        ResourceType::Script
    ));
}

/// Decisions checked against Adblock Plus' own pattern implementation. The audit that
/// produced these ran `patterns.js` from adblockpluscore; they pin the two cases where this
/// engine used to disagree with the reference.
#[test]
fn abp_reference_decisions_hold() {
    // `%` is not a separator, so `^` must not match inside a percent-encoded path.
    let e = engine(&["/ads^"]);
    assert!(!e.should_block("https://x.com/ads%20foo", DOC, ResourceType::Image));
    assert!(e.should_block("https://x.com/ads?q=1", DOC, ResourceType::Image));
    assert!(e.should_block("https://x.com/ads", DOC, ResourceType::Image));

    // A domain anchor constrains only where the match starts, so a bare host also covers
    // hosts that begin with it. `||ads.com*` already behaved this way, which was the tell.
    for rule in ["||ads.com", "||ads.com*"] {
        let e = engine(&[rule]);
        assert!(e.should_block("https://ads.com/a.js", DOC, ResourceType::Script));
        assert!(e.should_block("https://x.ads.com/a.js", DOC, ResourceType::Script));
        assert!(
            e.should_block("https://ads.com.evil.net/a.js", DOC, ResourceType::Script),
            "{rule} must cover a host that starts with it"
        );
        assert!(!e.should_block("https://notads.com/a.js", DOC, ResourceType::Script));
    }

    // The separator form keeps the stricter meaning: `.` is not a separator, so the
    // look-alike host stays allowed.
    let e = engine(&["||ads.com^"]);
    assert!(!e.should_block("https://ads.com.evil.net/a.js", DOC, ResourceType::Script));
    assert!(e.should_block("https://ads.com/x.js", DOC, ResourceType::Script));
}

/// EasyPrivacy writes "any TLD" as a trailing dot (`||adservice.google.`). Those rules were
/// dead entries: the hostname guard required the request host to equal the rule host, which
/// a trailing dot can never satisfy.
#[test]
fn trailing_dot_host_rule_covers_any_tld() {
    let e = engine(&["||adservice.google.", "||142.91.159."]);
    assert!(e.should_block(
        "https://adservice.google.com/x.js",
        DOC,
        ResourceType::Script
    ));
    assert!(e.should_block(
        "https://adservice.google.co.uk/x.js",
        DOC,
        ResourceType::Script
    ));
    assert!(e.should_block("https://142.91.159.100/x.js", DOC, ResourceType::Script));
    assert!(!e.should_block(
        "https://notadservice.google.com/x.js",
        DOC,
        ResourceType::Script
    ));
    assert!(!e.should_block(
        "https://xadservice.google.com/x.js",
        DOC,
        ResourceType::Script
    ));
    // The anchor only constrains where the match starts, so a host that continues past the
    // dot is covered too — the same rule as `||ads.com` above.
    assert!(e.should_block(
        "https://adservice.google.com.evil.net/x.js",
        DOC,
        ResourceType::Other
    ));
}

/// A page picks its own URL, so host length must not multiply the cost of the domain probe.
/// The ratio between a long host and a short one has to stay far below the quadratic
/// blow-up; this compares the two rather than an absolute time, so a slow machine moves both
/// numbers together. Before the guard was made single-pass and the suffix probe bounded, a
/// 4,000-label host cost about 15x the time of a 1,000-label one (and 90 ms in release).
#[test]
fn host_length_does_not_multiply_domain_probe_cost() {
    use std::time::Instant;
    let mut e = Engine::new();
    e.add_list(include_str!(
        "../../../app/src/main/assets/filters/easylist.txt"
    ));
    e.add_list(include_str!(
        "../../../app/src/main/assets/filters/easyprivacy.txt"
    ));
    e.add_list(include_str!(
        "../../../app/src/main/assets/filters/easylist-china.txt"
    ));

    let measure = |labels: usize| {
        let url = format!("https://{}google.com/pagead/lvz?x=1", "a.".repeat(labels));
        let mut best = f64::MAX;
        for _ in 0..5 {
            let started = Instant::now();
            for _ in 0..20 {
                black_box(e.should_block(&url, DOC, ResourceType::Script));
            }
            best = best.min(started.elapsed().as_secs_f64() / 20.0);
        }
        best
    };

    let short = measure(1_000);
    let long = measure(4_000);
    assert!(
        long < short * 8.0,
        "4x the host length must stay well under 16x the cost: {short:.6}s vs {long:.6}s"
    );
}

/// The indices cannot place patterns without a literal triplet, so those are scanned for
/// every request. The bucket is capped and loads report the shortfall instead of growing
/// without limit.
#[test]
fn fallback_bucket_is_capped_and_reported() {
    let rules: String = (0..5_000).map(|_| "a*b*c*d*e*\n").collect();
    let mut e = Engine::new();
    e.add_list(&rules);
    assert!(
        e.stats().refused_by_limit > 0,
        "the cap must be reported as a capacity refusal"
    );
    assert_eq!(
        e.stats().unsupported_skipped,
        0,
        "a truncated list is not unsupported syntax"
    );
    assert!(
        e.rule_count() < 5_000,
        "rules past the cap are not retained"
    );

    // The budget must not disturb ordinary decisions: a rule inside the cap still decides.
    let mut e = Engine::new();
    e.add_list("a*b*c*d*e*\n/x*y*z*\n");
    assert!(e.should_block("https://example.com/x1y2z3.js", DOC, ResourceType::Script));
    assert!(!e.should_block("https://example.com/plain.js", DOC, ResourceType::Script));
}
