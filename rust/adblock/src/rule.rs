//! A single parsed filter rule.
//!
//! This is an EasyList *subset*: the network-level syntax a WebView can actually act on
//! from `shouldInterceptRequest`. Cosmetic rules (`##selector`) are parsed only far enough
//! to be recognised and skipped -- hiding elements needs CSS injection, not request
//! blocking, so they are out of scope here and are counted as `skipped` rather than
//! silently dropped.

/// Resource type of a request, matched against `$`-options.
///
/// Mirrors the subset of EasyList option names that map onto something WebView reports.
#[derive(Copy, Clone, PartialEq, Eq, Debug)]
pub enum ResourceType {
    Document,
    Subdocument,
    Script,
    Stylesheet,
    Image,
    Font,
    Media,
    XmlHttpRequest,
    Ping,
    WebSocket,
    Other,
}

impl ResourceType {
    /// Bit used in `RuleOptions::types`. One bit per variant keeps the type filter a single
    /// `u16` test instead of a list walk.
    fn bit(self) -> u16 {
        match self {
            ResourceType::Document => 1 << 0,
            ResourceType::Subdocument => 1 << 1,
            ResourceType::Script => 1 << 2,
            ResourceType::Stylesheet => 1 << 3,
            ResourceType::Image => 1 << 4,
            ResourceType::Font => 1 << 5,
            ResourceType::Media => 1 << 6,
            ResourceType::XmlHttpRequest => 1 << 7,
            ResourceType::Ping => 1 << 8,
            ResourceType::WebSocket => 1 << 9,
            ResourceType::Other => 1 << 10,
        }
    }

    pub fn from_option_name(name: &str) -> Option<ResourceType> {
        match name {
            "document" | "doc" => Some(ResourceType::Document),
            "subdocument" | "frame" => Some(ResourceType::Subdocument),
            "script" => Some(ResourceType::Script),
            "stylesheet" | "css" => Some(ResourceType::Stylesheet),
            "image" => Some(ResourceType::Image),
            "font" => Some(ResourceType::Font),
            "media" => Some(ResourceType::Media),
            "xmlhttprequest" | "xhr" => Some(ResourceType::XmlHttpRequest),
            "ping" | "beacon" => Some(ResourceType::Ping),
            "websocket" => Some(ResourceType::WebSocket),
            "other" => Some(ResourceType::Other),
            _ => None,
        }
    }
}

/// Where a rule's pattern is allowed to sit inside the URL.
#[derive(Copy, Clone, PartialEq, Eq, Debug)]
pub enum Anchor {
    /// `||example.com/x` -- match at a domain boundary.
    Domain,
    /// `|http://x` -- match at the very start of the URL.
    Start,
    /// bare `foo/bar` -- match anywhere.
    None,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct RuleOptions {
    /// Type bits this rule applies to. 0 means "any type".
    pub types: u16,
    /// Type bits this rule is explicitly excluded from (`$~script`).
    pub excluded_types: u16,
    /// `$domain=a.com|b.com` -- rule only applies on these first-party domains.
    pub domains: Vec<String>,
    /// `$domain=~a.com` -- rule never applies on these.
    pub excluded_domains: Vec<String>,
    /// `$third-party` / `$~third-party`. `None` means "don't care".
    pub third_party: Option<bool>,
    /// `$match-case` -- pattern is case-sensitive. Rare; default is insensitive.
    pub match_case: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Rule {
    /// The pattern with anchors and options stripped, lowercased unless `match_case`.
    pub pattern: String,
    pub anchor: Anchor,
    /// Pattern ended with `|`: must reach the end of the URL.
    pub anchor_end: bool,
    /// `@@` exception -- if this matches, the request is explicitly allowed.
    pub is_exception: bool,
    pub options: RuleOptions,
}

/// Why a line produced no rule. Kept distinct so the load stats can tell "this list had
/// cosmetic rules we ignore" from "this list has syntax we failed on".
#[derive(PartialEq, Eq, Debug)]
pub enum SkipReason {
    Blank,
    Comment,
    Cosmetic,
    Unsupported,
}

impl Rule {
    /// Parse one EasyList line.
    ///
    /// Returns `Err(SkipReason)` for lines that are legitimately not network rules, so the
    /// caller can count them rather than treat them as failures.
    pub fn parse(line: &str) -> Result<Rule, SkipReason> {
        let line = line.trim();
        if line.is_empty() {
            return Err(SkipReason::Blank);
        }
        // `!` is the EasyList comment marker; `[Adblock Plus 2.0]` is the header line.
        if line.starts_with('!') || (line.starts_with('[') && line.ends_with(']')) {
            return Err(SkipReason::Comment);
        }
        // Cosmetic rules: `##sel`, `example.com##sel`, `#@#sel`, `#?#sel`.
        // Checked before option splitting because their selectors can contain `$`.
        if line.contains("##") || line.contains("#@#") || line.contains("#?#") {
            return Err(SkipReason::Cosmetic);
        }

        let (mut body, is_exception) = match line.strip_prefix("@@") {
            Some(rest) => (rest, true),
            None => (line, false),
        };

        // Split trailing `$options`. Regex-literal rules (`/re/`) are unsupported, and their
        // bodies can contain `$`, so bail before splitting on it.
        if body.starts_with('/') && body.ends_with('/') && body.len() > 1 {
            return Err(SkipReason::Unsupported);
        }
        let mut options = RuleOptions::default();
        if let Some(idx) = body.rfind('$') {
            let (pat, opts) = body.split_at(idx);
            options = parse_options(&opts[1..])?;
            body = pat;
        }

        let anchor;
        if let Some(rest) = body.strip_prefix("||") {
            anchor = Anchor::Domain;
            body = rest;
        } else if let Some(rest) = body.strip_prefix('|') {
            anchor = Anchor::Start;
            body = rest;
        } else {
            anchor = Anchor::None;
        }

        let anchor_end = body.ends_with('|');
        if anchor_end {
            body = &body[..body.len() - 1];
        }

        if body.is_empty() {
            return Err(SkipReason::Unsupported);
        }

        let pattern = if options.match_case {
            body.to_string()
        } else {
            body.to_lowercase()
        };

        Ok(Rule {
            pattern,
            anchor,
            anchor_end,
            is_exception,
            options,
        })
    }

    /// True if this rule's `$`-options permit it to apply to this request at all.
    ///
    /// Separated from pattern matching because the option test is cheap and rejects most
    /// candidate rules before the (more expensive) wildcard walk runs.
    pub fn options_apply(
        &self,
        resource: ResourceType,
        first_party_domain: &str,
        is_third_party: bool,
    ) -> bool {
        let o = &self.options;

        if o.excluded_types & resource.bit() != 0 {
            return false;
        }
        if o.types != 0 && o.types & resource.bit() == 0 {
            return false;
        }
        if let Some(want_third) = o.third_party {
            if want_third != is_third_party {
                return false;
            }
        }
        if !o.excluded_domains.is_empty()
            && o.excluded_domains
                .iter()
                .any(|d| domain_matches(first_party_domain, d))
        {
            return false;
        }
        if !o.domains.is_empty()
            && !o
                .domains
                .iter()
                .any(|d| domain_matches(first_party_domain, d))
        {
            return false;
        }
        true
    }
}

/// True if `host` is `domain` or a subdomain of it.
///
/// Compares on a label boundary so `notexample.com` does not match `example.com`.
pub fn domain_matches(host: &str, domain: &str) -> bool {
    if host == domain {
        return true;
    }
    host.len() > domain.len()
        && host.ends_with(domain)
        && host.as_bytes()[host.len() - domain.len() - 1] == b'.'
}

fn parse_options(spec: &str) -> Result<RuleOptions, SkipReason> {
    let mut out = RuleOptions::default();
    for raw in spec.split(',') {
        let raw = raw.trim();
        if raw.is_empty() {
            continue;
        }
        let (negated, name) = match raw.strip_prefix('~') {
            Some(rest) => (true, rest),
            None => (false, raw),
        };

        if let Some(list) = name.strip_prefix("domain=") {
            for d in list.split('|') {
                let d = d.trim().to_lowercase();
                if d.is_empty() {
                    continue;
                }
                match d.strip_prefix('~') {
                    Some(neg) => out.excluded_domains.push(neg.to_string()),
                    None => out.domains.push(d),
                }
            }
            continue;
        }

        match name {
            "third-party" | "3p" => out.third_party = Some(!negated),
            "match-case" => out.match_case = true,
            // Options that change *how* a response is rewritten rather than whether it is
            // blocked. Accepting them as plain blocks would be wrong, so skip the rule.
            "csp" | "redirect" | "redirect-rule" | "removeparam" | "replace" | "rewrite" => {
                return Err(SkipReason::Unsupported)
            }
            _ => {
                if let Some(t) = ResourceType::from_option_name(name) {
                    if negated {
                        out.excluded_types |= t.bit();
                    } else {
                        out.types |= t.bit();
                    }
                } else if name.starts_with("_") || name.is_empty() {
                    // Anti-adblock padding; ignore.
                } else {
                    // Unknown option: skip rather than over-block on a rule we only
                    // partly understand.
                    return Err(SkipReason::Unsupported);
                }
            }
        }
    }
    Ok(out)
}
