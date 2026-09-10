//! Reproducible host microbenchmark using the exact bundled subscription snapshots.
//! Run with `cargo run --release -p adblock --example benchmark -- 20`.
use adblock::{engine::Engine, rule::ResourceType};
use std::{hint::black_box, path::Path, time::Instant};

fn main() {
    let rounds = std::env::args()
        .nth(1)
        .and_then(|value| value.parse::<usize>().ok())
        .unwrap_or(20)
        .clamp(1, 10_000);
    let assets = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../app/src/main/assets/filters");
    let started = Instant::now();
    let mut engine = Engine::new();
    for name in ["easylist.txt", "easyprivacy.txt", "easylist-china.txt"] {
        engine.add_list(&std::fs::read_to_string(assets.join(name)).expect("bundled list"));
    }
    let load_ms = started.elapsed().as_secs_f64() * 1000.0;
    let long_url = format!(
        "https://cdn.example.org/movie/segment.m4s?token={}",
        "abcdef0123456789".repeat(128)
    );
    let cases = [
        (
            "https://www.example.org/",
            "https://www.example.org/",
            ResourceType::Document,
        ),
        (
            "https://cdn.example.org/assets/main.js",
            "https://www.example.org/",
            ResourceType::Script,
        ),
        (
            "https://cdn.example.org/images/photo.webp",
            "https://www.example.org/",
            ResourceType::Image,
        ),
        (
            "https://fonts.gstatic.com/s/roboto/font.woff2",
            "https://www.example.org/",
            ResourceType::Font,
        ),
        (
            "https://m.youtube.com/youtubei/v1/player",
            "https://m.youtube.com/watch?v=aqz-KE-bpKQ",
            ResourceType::XmlHttpRequest,
        ),
        (
            "https://rr1.googlevideo.com/videoplayback?id=123&mime=video/mp4",
            "https://m.youtube.com/",
            ResourceType::Media,
        ),
        (
            "https://media.example.org/live/vl.m3u8?token=ABC123",
            "https://m.jrs16.com/wlty.html",
            ResourceType::Media,
        ),
        (
            "https://media.example.org/live/segment-001.ts",
            "https://m.jrs16.com/wlty.html",
            ResourceType::Media,
        ),
        (
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://www.example.org/",
            ResourceType::Script,
        ),
        (
            "https://www.google-analytics.com/analytics.js",
            "https://www.example.org/",
            ResourceType::Script,
        ),
        (
            "https://cdn.example.org/ads/banner.js",
            "https://www.example.org/",
            ResourceType::Script,
        ),
        (
            long_url.as_str(),
            "https://www.example.org/",
            ResourceType::Media,
        ),
    ];
    let decisions: Vec<_> = cases
        .iter()
        .map(|(url, page, kind)| engine.should_block(url, page, *kind))
        .collect();
    let mut samples = Vec::with_capacity(rounds * cases.len());
    for _ in 0..rounds {
        for (url, page, kind) in cases {
            let started = Instant::now();
            black_box(engine.should_block(black_box(url), black_box(page), kind));
            samples.push(started.elapsed().as_secs_f64() * 1_000_000.0);
        }
    }
    let total_us: f64 = samples.iter().sum();
    samples.sort_by(f64::total_cmp);
    println!("{{\"rules\":{},\"requests\":{},\"load_ms\":{:.3},\"total_ms\":{:.3},\"mean_us\":{:.3},\"median_us\":{:.3},\"p95_us\":{:.3},\"decisions\":{:?}}}",
        engine.rule_count(), samples.len(), load_ms, total_us / 1000.0,
        total_us / samples.len() as f64, samples[samples.len() / 2],
        samples[(samples.len() * 95 / 100).min(samples.len() - 1)], decisions);
}
