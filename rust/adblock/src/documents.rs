use crate::matcher::DocumentContext;
use std::collections::VecDeque;
use std::sync::Arc;

const MAX_CACHED_DOCUMENTS: usize = 32;
const MAX_DOCUMENT_URL_BYTES: usize = 32 * 1024;

/// Bounded LRU of parsed first-party document contexts. `Arc` keeps evicted contexts alive for
/// in-flight readers without requiring two-step ID management across JNI.
#[derive(Default)]
pub(crate) struct Documents {
    items: VecDeque<(String, Arc<DocumentContext>)>,
}

impl Documents {
    pub fn get_or_prepare(&mut self, url: &str) -> Arc<DocumentContext> {
        if let Some(index) = self.items.iter().rposition(|(key, _)| key == url) {
            let last = self.items.len() - 1;
            if index != last {
                let entry = self.items.remove(index).expect("checked index");
                let context = Arc::clone(&entry.1);
                self.items.push_back(entry);
                return context;
            }
            return Arc::clone(&self.items[index].1);
        }
        let context = Arc::new(DocumentContext::new(url));
        if url.len() <= MAX_DOCUMENT_URL_BYTES {
            if self.items.len() >= MAX_CACHED_DOCUMENTS {
                self.items.pop_front();
            }
            self.items.push_back((url.to_owned(), Arc::clone(&context)));
        }
        context
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn lru_eviction_keeps_existing_reader_alive_and_promotes_recent_hits() {
        let mut documents = Documents::default();
        let first = documents.get_or_prepare("https://www.example.com");
        let second = documents.get_or_prepare("https://www.second.test");
        assert!(Arc::ptr_eq(
            &first,
            &documents.get_or_prepare("https://www.example.com")
        ));
        for i in 0..31 {
            documents.get_or_prepare(&format!("https://site{i}.test"));
        }
        assert_eq!(documents.items.len(), 32);
        // `second` was least-recently used and evicted (`strong_count == 1`), while `first`
        // was promoted on hit and remains cached (`strong_count == 2`).
        assert_eq!(Arc::strong_count(&second), 1);
        assert_eq!(Arc::strong_count(&first), 2);
    }
}
