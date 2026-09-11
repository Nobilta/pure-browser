//! Reproducible generated inputs: the indexed hot path must agree with a linear scan.
use adblock::matcher::{DocumentContext, Matcher};
use adblock::rule::ResourceType;

#[test]
fn generated_unicode_rules_and_urls_keep_indexed_and_linear_decisions_equal() {
    let hosts = [
        "example.com",
        "cdn.example.com",
        "a.github.io",
        "b.github.io",
        "例子.中国",
        "xn--fsqu00a.xn--fiqs8s",
        "[::1]",
    ];
    let options = [
        "",
        "$third-party",
        "$~third-party",
        "$script",
        "$image",
        "$domain=example.com",
        "$match-case",
    ];
    let tokens = [
        "ad", "assets", "广告", "A_B", "foo^bar", "*banner*", "%2F", "\0",
    ];
    let mut seed = 0x6a09e667u64;
    let mut next = || {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        seed as usize
    };
    for _ in 0..96 {
        let mut content = String::new();
        for i in 0..24 {
            content.push_str(&format!(
                "{}||{}/{}{}\n",
                if i % 5 == 0 { "@@" } else { "" },
                hosts[next() % hosts.len()],
                tokens[next() % tokens.len()],
                options[next() % options.len()]
            ));
        }
        let mut matcher = Matcher::new();
        matcher.load(&content);
        for _ in 0..48 {
            let url = format!(
                "https://{}/{}?q=中文%20{}",
                hosts[next() % hosts.len()],
                tokens[next() % tokens.len()],
                next()
            );
            let page = format!("https://{}/document", hosts[next() % hosts.len()]);
            let kind = if next() % 2 == 0 {
                ResourceType::Script
            } else {
                ResourceType::Image
            };
            let (blocking, exception) = Matcher::explain(&content, &url, &page, kind);
            let expected = blocking.is_some() && exception.is_none();
            assert_eq!(
                matcher.should_block(&url, &page, kind),
                expected,
                "{url} from {page}\n{content}"
            );
            assert_eq!(
                matcher.should_block_context(&url, &DocumentContext::new(&page), kind),
                expected
            );
            assert!(!matcher.should_block("data:text/plain,ad", &page, kind));
        }
    }
}
