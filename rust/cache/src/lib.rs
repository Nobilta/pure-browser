//! Cache management with LRU eviction policy.
//!
//! Provides a high-performance LRU cache for thumbnails and favicons
//! to reduce GC pressure on the Android side.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};

struct LruCache {
    capacity: usize,
    map: HashMap<String, CacheEntry>,
    access_order: VecDeque<String>,
    total_bytes: usize,
}

struct CacheEntry {
    data: Vec<u8>,
    size: usize,
}

impl LruCache {
    fn new(capacity: usize) -> Self {
        Self {
            capacity: capacity.clamp(1, MAX_CAPACITY),
            map: HashMap::new(),
            access_order: VecDeque::new(),
            total_bytes: 0,
        }
    }

    fn get(&mut self, key: &str) -> Option<&[u8]> {
        if self.map.contains_key(key) {
            // Move to end (most recently used)
            if let Some(pos) = self.access_order.iter().position(|k| k == key) {
                self.access_order.remove(pos);
                self.access_order.push_back(key.to_string());
            }
            self.map.get(key).map(|e| e.data.as_slice())
        } else {
            None
        }
    }

    fn put(&mut self, key: String, data: Vec<u8>) -> bool {
        let size = data.len();

        // An entry larger than the whole cache can never be retained. Do not evict every
        // existing thumbnail only to insert an item that immediately violates the cap.
        if !valid_key(&key) || size > self.capacity || size > MAX_ENTRY_BYTES {
            return false;
        }

        // Remove old entry if exists
        if let Some(old) = self.map.remove(&key) {
            self.total_bytes = self.total_bytes.saturating_sub(old.size);
            if let Some(pos) = self.access_order.iter().position(|k| k == &key) {
                self.access_order.remove(pos);
            }
        }

        // Evict LRU entries if over capacity
        while self.total_bytes.saturating_add(size) > self.capacity {
            let Some(lru_key) = self.access_order.pop_front() else {
                break;
            };
            if let Some(old) = self.map.remove(&lru_key) {
                self.total_bytes = self.total_bytes.saturating_sub(old.size);
            }
        }

        // Add new entry
        self.map.insert(key.clone(), CacheEntry { data, size });
        self.access_order.push_back(key);
        self.total_bytes = self.total_bytes.saturating_add(size);
        true
    }

    fn remove(&mut self, key: &str) -> bool {
        if let Some(entry) = self.map.remove(key) {
            self.total_bytes = self.total_bytes.saturating_sub(entry.size);
            if let Some(pos) = self.access_order.iter().position(|k| k == key) {
                self.access_order.remove(pos);
            }
            true
        } else {
            false
        }
    }

    fn clear(&mut self) {
        self.map.clear();
        self.access_order.clear();
        self.total_bytes = 0;
    }

    fn entry_count(&self) -> usize {
        self.map.len()
    }
}

// JNI Bridge

const MAX_CAPACITY: usize = 512 * 1024 * 1024;
const MAX_KEY_BYTES: usize = 1024;
const MAX_ENTRY_BYTES: usize = 64 * 1024 * 1024;

fn valid_key(key: &str) -> bool {
    !key.is_empty() && key.len() <= MAX_KEY_BYTES
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_data_NativeCache_nativeNew(
    _env: JNIEnv,
    _class: JClass,
    capacity_bytes: jlong,
) -> jlong {
    let capacity = if capacity_bytes <= 0 {
        1
    } else {
        (capacity_bytes as u128).min(MAX_CAPACITY as u128) as usize
    };
    let cache = Box::new(Arc::new(Mutex::new(LruCache::new(capacity))));
    Box::into_raw(cache) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_data_NativeCache_nativeFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            let _ = Box::from_raw(handle as *mut Arc<Mutex<LruCache>>);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_data_NativeCache_nativeGet(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JString,
) -> jbyteArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }

    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    if !valid_key(&key) {
        return std::ptr::null_mut();
    }

    let cache_arc = unsafe { &*(handle as *const Arc<Mutex<LruCache>>) };
    let mut cache = match cache_arc.lock() {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };

    match cache.get(&key) {
        Some(data) => match env.byte_array_from_slice(data) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_data_NativeCache_nativePut(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JString,
    data: JByteArray,
) -> jboolean {
    if handle == 0 {
        return 0;
    }

    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    if !valid_key(&key) {
        return 0;
    }

    // Check the JNI array length before copying it into a Rust Vec. This keeps an
    // accidental multi-hundred-megabyte Java array outside the native heap.
    let length = match env.get_array_length(&data) {
        Ok(value) if value >= 0 => value as usize,
        _ => return 0,
    };
    if length > MAX_ENTRY_BYTES {
        return 0;
    }

    let data_vec: Vec<u8> = match env.convert_byte_array(data) {
        Ok(v) => v,
        Err(_) => return 0,
    };

    let cache_arc = unsafe { &*(handle as *const Arc<Mutex<LruCache>>) };
    let mut cache = match cache_arc.lock() {
        Ok(c) => c,
        Err(_) => return 0,
    };

    cache.put(key, data_vec) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_data_NativeCache_nativeRemove(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JString,
) -> jboolean {
    if handle == 0 {
        return 0;
    }

    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    if !valid_key(&key) {
        return 0;
    }

    let cache_arc = unsafe { &*(handle as *const Arc<Mutex<LruCache>>) };
    let mut cache = match cache_arc.lock() {
        Ok(c) => c,
        Err(_) => return 0,
    };

    if cache.remove(&key) {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_data_NativeCache_nativeClear(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }

    let cache_arc = unsafe { &*(handle as *const Arc<Mutex<LruCache>>) };
    if let Ok(mut cache) = cache_arc.lock() {
        cache.clear();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_data_NativeCache_nativeSize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }

    let cache_arc = unsafe { &*(handle as *const Arc<Mutex<LruCache>>) };
    match cache_arc.lock() {
        Ok(cache) => cache.entry_count() as jint,
        Err(_) => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::LruCache;

    #[test]
    fn evicts_least_recently_used_entry() {
        let mut cache = LruCache::new(4);
        assert!(cache.put("a".into(), vec![1, 2]));
        assert!(cache.put("b".into(), vec![3, 4]));
        assert_eq!(cache.get("a"), Some(&[1, 2][..]));
        assert!(cache.put("c".into(), vec![5, 6]));
        assert!(cache.get("a").is_some());
        assert!(cache.get("b").is_none());
        assert_eq!(cache.entry_count(), 2);
    }

    #[test]
    fn rejects_invalid_keys_and_oversized_entries() {
        let mut cache = LruCache::new(1024);
        assert!(!cache.put(String::new(), vec![1]));
        assert!(!cache.put("x".repeat(1025), vec![1]));
        assert!(!cache.put("large".into(), vec![0; 1025]));
    }
}
