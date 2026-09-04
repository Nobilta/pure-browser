//! EasyList network-rule filter for Android WebView `shouldInterceptRequest`.
//!
//! This is the Rust half: parsing, indexing, and wildcard matching. The Kotlin side feeds
//! it URLs from the WebView callbacks and gets back "block" / "allow" decisions.
//!
//! The Kotlin class is `com.mybrowser.filter.NativeFilter`. Each instance owns a `Matcher`
//! via an opaque handle (the pointer cast to `jlong`). Thread-safe for reads once loaded,
//! but `addList` must finish before any `shouldBlock` calls happen.

pub mod engine;
pub mod matcher;
pub mod rule;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use matcher::Matcher;
use rule::ResourceType;

/// Create a new empty engine. Returns an opaque handle (Matcher pointer as jlong).
#[no_mangle]
pub extern "system" fn Java_com_mybrowser_filter_NativeFilter_nativeNew(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let matcher = Box::new(Matcher::new());
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
            let _ = Box::from_raw(handle as *mut Matcher);
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

    let matcher = unsafe { &mut *(handle as *mut Matcher) };
    let _stats = matcher.load(&text);
    matcher.rule_count().min(jint::MAX as usize) as jint
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

    let resource_type = match resource_type_ordinal {
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
    };

    let matcher = unsafe { &*(handle as *const Matcher) };
    if matcher.should_block(&request_url, &document_url, resource_type) {
        1
    } else {
        0
    }
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
    let matcher = unsafe { &*(handle as *const Matcher) };
    matcher.rule_count().min(jint::MAX as usize) as jint
}
