//! EasyList network-rule filter for Android WebView `shouldInterceptRequest`.
//!
//! This is the Rust half: parsing, indexing, and wildcard matching. The Kotlin side feeds
//! it URLs from the WebView callbacks and gets back "block" / "allow" decisions.
//!
//! The Kotlin class is `com.mybrowser.filter.NativeFilter`. Each instance owns a `Matcher`
//! via an opaque handle (the pointer cast to `jlong`). Thread-safe for reads once loaded,
//! but `addList` must finish before any `shouldBlock` calls happen.

pub mod cosmetic;
mod documents;
pub mod engine;
pub mod matcher;
pub mod rule;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use matcher::Matcher;
use rule::ResourceType;

#[derive(Default)]
struct FilterEngine {
    network: Matcher,
    cosmetic: cosmetic::CosmeticMatcher,
    documents: std::sync::Mutex<documents::Documents>,
    unsupported: usize,
}

/// Create a new empty engine. Returns an opaque handle (Matcher pointer as jlong).
#[no_mangle]
pub extern "system" fn Java_com_mybrowser_filter_NativeFilter_nativeNew(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let matcher = Box::new(FilterEngine::default());
    Box::into_raw(matcher) as jlong
}

/// Free the engine. The handle becomes invalid.
#[no_mangle]
pub extern "system" fn Java_com_mybrowser_filter_NativeFilter_nativeFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            let _ = Box::from_raw(handle as *mut FilterEngine);
        }
    }
}

/// Parse `text` as an EasyList-syntax filter list and add its network rules.
/// Returns the total rule count after loading, or -1 on failure.
#[no_mangle]
pub extern "system" fn Java_com_mybrowser_filter_NativeFilter_nativeAddList(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    text: JString,
) -> jint {
    if handle == 0 {
        return -1;
    }

    let text: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };

    let engine = unsafe { &mut *(handle as *mut FilterEngine) };
    engine.unsupported += engine.network.load(&text).skipped_unsupported;
    engine.cosmetic.load(&text);
    engine.network.rule_count().min(jint::MAX as usize) as jint
}

/// True if this request should be blocked.
#[no_mangle]
pub extern "system" fn Java_com_mybrowser_filter_NativeFilter_nativeShouldBlock(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    request_url: JString,
    document_url: JString,
    resource_type_ordinal: jint,
) -> jboolean {
    if handle == 0 {
        return 0;
    }

    let request_url: String = match env.get_string(&request_url) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let document_url: String = match env.get_string(&document_url) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let resource_type = resource_from_ordinal(resource_type_ordinal);

    let engine = unsafe { &*(handle as *const FilterEngine) };
    u8::from(
        engine
            .network
            .should_block(&request_url, &document_url, resource_type),
    )
}

fn resource_from_ordinal(value: jint) -> ResourceType {
    match value {
        0 => ResourceType::Document,
        1 => ResourceType::Subdocument,
        2 => ResourceType::Script,
        3 => ResourceType::Stylesheet,
        4 => ResourceType::Image,
        5 => ResourceType::Font,
        6 => ResourceType::Media,
        7 => ResourceType::XmlHttpRequest,
        8 => ResourceType::Ping,
        9 => ResourceType::WebSocket,
        _ => ResourceType::Other,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_filter_NativeFilter_nativePrepareDocument(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
) -> jlong {
    if handle == 0 {
        return 0;
    }
    let url: String = match env.get_string(&url) {
        Ok(value) => value.into(),
        Err(_) => return 0,
    };
    let engine = unsafe { &*(handle as *const FilterEngine) };
    engine
        .documents
        .lock()
        .map(|mut cache| cache.prepare(&url))
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_filter_NativeFilter_nativeCheckDocument(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    request_url: JString,
    document_id: jlong,
    resource: jint,
) -> jint {
    if handle == 0 {
        return -1;
    }
    let engine = unsafe { &*(handle as *const FilterEngine) };
    let document = engine
        .documents
        .lock()
        .ok()
        .and_then(|cache| cache.get(document_id));
    let Some(document) = document else {
        return -1;
    };
    let url: String = match env.get_string(&request_url) {
        Ok(value) => value.into(),
        Err(_) => return 0,
    };
    if url.len() > 32 * 1024 {
        return 0;
    }
    i32::from(
        engine
            .network
            .should_block_context(&url, &document, resource_from_ordinal(resource)),
    )
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_filter_NativeFilter_nativeUnsupportedCount(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let engine = unsafe { &*(handle as *const FilterEngine) };
    engine.unsupported.min(jint::MAX as usize) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_filter_NativeFilter_nativeExplainList(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    request: JString,
    document: JString,
    resource: jint,
) -> jstring {
    let text: String = match env.get_string(&text) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let request: String = match env.get_string(&request) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let document: String = match env.get_string(&document) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    if text.len() > 8 * 1024 * 1024 || request.len() > 32 * 1024 || document.len() > 32 * 1024 {
        return std::ptr::null_mut();
    }
    let (blocking, exception) =
        Matcher::explain(&text, &request, &document, resource_from_ordinal(resource));
    let result = format!(
        "{}\n{}",
        blocking.unwrap_or_default(),
        exception.unwrap_or_default()
    );
    env.new_string(result)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Return the number of network rules currently loaded.
#[no_mangle]
pub extern "system" fn Java_com_mybrowser_filter_NativeFilter_nativeRuleCount(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let engine = unsafe { &*(handle as *const FilterEngine) };
    engine.network.rule_count().min(jint::MAX as usize) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_filter_NativeFilter_nativeCosmeticRuleCount(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let engine = unsafe { &*(handle as *const FilterEngine) };
    engine.cosmetic.rule_count().min(jint::MAX as usize) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_filter_NativeFilter_nativeCosmeticCss(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    url: JString,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let url: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let engine = unsafe { &*(handle as *const FilterEngine) };
    env.new_string(engine.cosmetic.css_for(&url).as_ref())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
