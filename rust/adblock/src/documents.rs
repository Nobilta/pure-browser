use crate::matcher::DocumentContext;
use std::collections::VecDeque;
use std::sync::Arc;

/// IDs belong to one engine generation. Arc keeps evicted contexts alive for readers.
#[derive(Default)]
pub(crate) struct Documents {
    next: i64,
    items: VecDeque<(i64, Arc<DocumentContext>)>,
}

impl Documents {
    pub fn prepare(&mut self, url: &str) -> i64 {
        if url.len() > 32 * 1024 {
            return 0;
        }
        let Some(id) = self.next.checked_add(1) else {
            return 0;
        };
        self.next = id;
        self.items
            .push_back((id, Arc::new(DocumentContext::new(url))));
        while self.items.len() > 32 {
            self.items.pop_front();
        }
        id
    }
    pub fn get(&self, id: i64) -> Option<Arc<DocumentContext>> {
        self.items
            .iter()
            .rev()
            .find(|(key, _)| *key == id)
            .map(|(_, item)| Arc::clone(item))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn eviction_keeps_existing_reader_alive_and_rejects_stale_ids() {
        let mut documents = Documents::default();
        let first = documents.prepare("https://www.example.com");
        let reader = documents.get(first).unwrap();
        for i in 0..64 {
            documents.prepare(&format!("https://site{i}.test"));
        }
        assert!(documents.get(first).is_none());
        assert_eq!(documents.items.len(), 32);
        assert!(Arc::strong_count(&reader) == 1);
    }
}
