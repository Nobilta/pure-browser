#!/usr/bin/env python3
"""Compare filter crate optimization levels using identical bundled lists and requests."""
import json
from pathlib import Path
import re
import statistics
import subprocess

root = Path(__file__).resolve().parents[1]
out = root / 'validation/results/system-upgrade/rust-profiles'
out.mkdir(parents=True, exist_ok=True)
results = []
for level in ('z', '2', '3'):
    setting = f'profile.release.package.adblock.opt-level={json.dumps(level) if level == "z" else level}'
    with (out / f'build-{level}.log').open('w') as log:
        subprocess.run(['cargo', 'build', '--release', '-p', 'adblock', '--lib', '--example', 'benchmark',
                        '--config', setting, '--locked'], cwd=root / 'rust', stdout=log, stderr=log, check=True)
    samples = []
    for run in range(6):
        process = subprocess.run(['/usr/bin/time', '-l', 'target/release/examples/benchmark', '100'], cwd=root / 'rust',
                                 text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        row = json.loads(process.stdout)
        rss = re.search(r'(\d+)\s+maximum resident set size', process.stderr)
        row['maxRssBytes'] = int(rss[1]) if rss else None
        if run: samples.append(row)
    library = root / 'rust/target/release/libadblock.dylib'
    result = {'optLevel': level, 'scope': 'macOS arm64 host; filter crate only, dependencies retain release z',
              'libraryBytes': library.stat().st_size, 'samples': samples,
              'medianP95Us': statistics.median(s['p95_us'] for s in samples),
              'medianLoadMs': statistics.median(s['load_ms'] for s in samples)}
    results.append(result)
    (out / 'result.json').write_text(json.dumps(results, indent=2))
    print(json.dumps({k: v for k, v in result.items() if k != 'samples'}), flush=True)
assert all(s['decisions'] == results[0]['samples'][0]['decisions'] for r in results for s in r['samples'])
