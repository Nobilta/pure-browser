//! Controlled host benchmark; JNI/DOM injection and page-load time are not measured.
use adblock::cosmetic::CosmeticMatcher;
use std::{env, fs, hint::black_box, path::PathBuf, time::Instant};

fn main() {
    let mut args = env::args().skip(1);
    let out = PathBuf::from(args.next().expect("output directory"));
    let payloads: Vec<_> = args.map(|p| fs::read_to_string(p).unwrap()).collect();
    let mut matcher = CosmeticMatcher::new();
    for payload in &payloads {
        matcher.load(payload);
    }
    let text = fs::read_to_string(out.join("hosts.txt")).unwrap();
    let hosts: Vec<_> = text.lines().collect();
    for (index, host) in hosts.iter().enumerate() {
        fs::write(
            out.join(format!("rust-{index}.css")),
            &*matcher.css_for(host),
        )
        .unwrap();
    }
    for _ in 0..3 {
        for host in &hosts {
            black_box(matcher.css_for(host));
        }
    }
    let mut cold = Vec::new();
    for _ in 0..6 {
        for host in &hosts {
            let started = Instant::now();
            black_box(matcher.css_for(host));
            cold.push(started.elapsed().as_nanos());
        }
    }
    for _ in 0..200 {
        black_box(matcher.css_for(hosts[0]));
    }
    let mut warm = Vec::new();
    for _ in 0..200 {
        let started = Instant::now();
        black_box(matcher.css_for(hosts[0]));
        warm.push(started.elapsed().as_nanos());
    }
    cold.sort_unstable();
    warm.sort_unstable();
    println!(
        "{{\"rules\":{},\"coldMedianNs\":{},\"warmMedianNs\":{},\"coldSamples\":{},\"warmSamples\":{}}}",
        matcher.rule_count(), cold[cold.len() / 2], warm[warm.len() / 2], cold.len(), warm.len()
    );
}
