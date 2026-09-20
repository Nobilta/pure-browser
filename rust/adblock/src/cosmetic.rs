//! Indexed, bounded element-hiding rules. The network and cosmetic engines share a
//! native lifetime, so Kotlin no longer parses or retains a second rule object graph.
use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};

const MAX_SELECTOR_BYTES: usize = 8_192;
const MAX_CSS_BYTES: usize = 2 * 1024 * 1024;
const MAX_CACHE_BYTES: usize = 8 * 1024 * 1024;
const MAX_CACHE_HOSTS: usize = 32;

#[derive(Debug)]
struct Entry {
    exclude: Vec<String>,
    selector: usize,
    exception: bool,
}

#[derive(Default)]
struct Cache {
    entries: VecDeque<(String, Arc<str>)>,
    bytes: usize,
}

#[derive(Default)]
pub struct CosmeticMatcher {
    entries: Vec<Entry>,
    selectors: Vec<Arc<str>>,
    selector_ids: HashMap<Arc<str>, usize>,
    generic: Vec<usize>,
    domains: HashMap<String, Vec<usize>>,
    cache: Mutex<Cache>,
}

impl CosmeticMatcher {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn load(&mut self, text: &str) {
        for line in text.lines().map(str::trim) {
            if line.starts_with('!') || line.len() > 16 * 1024 {
                continue;
            }
            let (marker, exception) = if line.contains("#@#") {
                ("#@#", true)
            } else {
                ("##", false)
            };
            let Some((domains, selector)) = line.split_once(marker) else {
                continue;
            };
            let selector = selector.trim();
            if selector.is_empty()
                || selector.len() > MAX_SELECTOR_BYTES
                || selector.chars().count() > 2_048
                || selector
                    .chars()
                    .any(|c| c.is_control() || "{}@".contains(c))
                || [
                    "+js(",
                    ":has-text(",
                    ":matches-css",
                    ":xpath(",
                    ":remove(",
                    ":style(",
                    ":-abp-",
                ]
                .iter()
                .any(|unsupported| selector.contains(unsupported))
            {
                continue;
            }
            let domains = domains.to_ascii_lowercase();
            let domains: Vec<&str> = domains.split(',').filter(|s| !s.is_empty()).collect();
            if domains.iter().any(|d| {
                let d = d.strip_prefix('~').unwrap_or(d);
                d.is_empty()
                    || !d
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b"_.-".contains(&b))
            }) {
                continue;
            }
            let mut include: Vec<&str> = domains
                .iter()
                .copied()
                .filter(|d| !d.starts_with('~'))
                .collect();
            include.sort_unstable();
            include.dedup();
            let exclude = domains
                .iter()
                .filter_map(|d| d.strip_prefix('~'))
                .map(str::to_owned)
                .collect();
            let index = self.entries.len();
            // Intern once while loading. Queries mark integer IDs instead of hashing
            // every long generic CSS selector again for each newly visited host.
            let selector = match self.selector_ids.get(selector) {
                Some(&id) => id,
                None => {
                    let id = self.selectors.len();
                    let text: Arc<str> = selector.into();
                    self.selectors.push(Arc::clone(&text));
                    self.selector_ids.insert(text, id);
                    id
                }
            };
            self.entries.push(Entry {
                exclude,
                selector,
                exception,
            });
            if include.is_empty() {
                self.generic.push(index);
            } else {
                for domain in include {
                    self.domains
                        .entry(domain.to_owned())
                        .or_default()
                        .push(index);
                }
            }
        }
        *self
            .cache
            .get_mut()
            .unwrap_or_else(|error| error.into_inner()) = Cache::default();
    }

    pub fn rule_count(&self) -> usize {
        self.entries.len()
    }

    /// Host input is validated here; a malformed or non-web URL never receives CSS.
    /// Only the host affects selection, so paths and queries cannot fill the cache.
    pub fn css_for(&self, url: &str) -> Arc<str> {
        if url.len() > 8_192 || url.chars().any(|c| c.is_control() || c.is_whitespace()) {
            return Arc::from("");
        }
        let lower = url.to_ascii_lowercase();
        let Some(host) = crate::matcher::extract_host(&lower).filter(|h| !h.is_empty()) else {
            return Arc::from("");
        };
        {
            let mut cache = self.cache.lock().unwrap_or_else(|error| error.into_inner());
            if let Some(index) = cache.entries.iter().position(|(key, _)| key == host) {
                if let Some(entry) = cache.entries.remove(index) {
                    let css = Arc::clone(&entry.1);
                    cache.entries.push_front(entry);
                    return css;
                }
            }
        }
        let css: Arc<str> = self.select(host).into();
        let mut cache = self.cache.lock().unwrap_or_else(|error| error.into_inner());
        // Concurrent readers can select the same new host. Keep one cache entry.
        if !cache.entries.iter().any(|(key, _)| key == host) {
            while cache.entries.len() >= MAX_CACHE_HOSTS
                || cache.bytes + css.len() > MAX_CACHE_BYTES
            {
                let Some((_, old)) = cache.entries.pop_back() else {
                    break;
                };
                cache.bytes -= old.len();
            }
            cache.bytes += css.len();
            cache
                .entries
                .push_front((host.to_owned(), Arc::clone(&css)));
        }
        css
    }

    fn candidates(&self, host: &str) -> Vec<usize> {
        let mut selected = self.generic.clone();
        let mut suffix = host;
        loop {
            if let Some(indices) = self.domains.get(suffix) {
                selected.extend_from_slice(indices);
            }
            let Some(dot) = suffix.find('.') else { break };
            suffix = &suffix[dot + 1..];
        }
        selected.sort_unstable();
        selected.dedup();
        selected.retain(|&i| {
            self.entries[i]
                .exclude
                .iter()
                .all(|d| !matches_domain(host, d))
        });
        selected
    }

    fn select(&self, host: &str) -> String {
        let selected = self.candidates(host);
        let mut suppressed = vec![false; self.selectors.len()];
        for &index in &selected {
            let entry = &self.entries[index];
            if entry.exception {
                suppressed[entry.selector] = true;
            }
        }
        let mut css = String::new();
        for index in selected {
            let entry = &self.entries[index];
            if entry.exception || suppressed[entry.selector] {
                continue;
            }
            suppressed[entry.selector] = true;
            let selector = &*self.selectors[entry.selector];
            const DECLARATION: &str = "{display:none!important;}";
            if css.len() + selector.len() + DECLARATION.len() + 1 > MAX_CSS_BYTES {
                break;
            }
            if !css.is_empty() {
                css.push('\n');
            }
            css.push_str(selector);
            css.push_str(DECLARATION);
        }
        css
    }
}

fn matches_domain(host: &str, domain: &str) -> bool {
    host == domain
        || host
            .strip_suffix(domain)
            .is_some_and(|prefix| prefix.ends_with('.'))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn matcher(lists: &[&str]) -> CosmeticMatcher {
        let mut result = CosmeticMatcher::new();
        for list in lists {
            result.load(list);
        }
        result
    }

    #[test]
    fn domain_exceptions_override_generic_and_domain_hiding() {
        let m = matcher(&["##.advert\nexample.com##.sponsor\nexample.com#@#.advert"]);
        assert_eq!(
            &*m.css_for("https://sub.example.com/news"),
            ".sponsor{display:none!important;}"
        );
        assert!(m.css_for("https://other.test/").contains(".advert"));
        assert!(!m.css_for("https://notexample.com/").contains(".sponsor"));
    }

    #[test]
    fn exclusions_invalid_domains_and_unsupported_syntax() {
        let m = matcher(&["~private.test##.ad\nexample.com,~safe.example.com##.extra\nbad*host##.invalid\n##+js(alert)\n##div:has-text(ad)\n##.x{color:red}"]);
        assert_eq!(m.rule_count(), 2);
        assert!(!m.css_for("https://private.test/").contains(".ad"));
        assert!(m.css_for("https://example.com/").contains(".extra"));
        assert!(!m.css_for("https://safe.example.com/").contains(".extra"));
    }

    #[test]
    fn cross_list_exceptions_duplicates_case_and_cache_invalidation() {
        let mut m = matcher(&["##.ad\n##.ad\nsite.test,sub.site.test##.local"]);
        assert!(m.css_for("HTTPS://SITE.TEST/a").contains(".ad"));
        m.load("site.test#@#.ad");
        assert_eq!(
            &*m.css_for("https://sub.site.test/b"),
            ".local{display:none!important;}"
        );
        assert_eq!(
            &*m.css_for("https://other.test/"),
            ".ad{display:none!important;}"
        );
        assert!(m.css_for("file://site.test/a").is_empty());
        assert!(m.css_for("https://").is_empty());
    }

    #[test]
    fn indexed_domain_selection_and_cache_are_bounded() {
        let mut m = CosmeticMatcher::new();
        for i in 0..200 {
            m.load(&format!("site{i}.test,sub.site{i}.test##.item{i}\n~private.test##.generic{i}\nsite{i}.test#@#.generic{i}"));
        }
        for i in 0..200 {
            let host = format!("sub.site{i}.test");
            let css = m.css_for(&format!("https://{host}/"));
            assert!(css.contains(&format!(".item{i}{{")));
            assert!(!css.contains(&format!(".generic{i}{{")));
            assert_eq!(css.lines().count(), 200);
        }
        let cache = m.cache.lock().unwrap();
        assert!(cache.entries.len() <= MAX_CACHE_HOSTS && cache.bytes <= MAX_CACHE_BYTES);
    }
}
