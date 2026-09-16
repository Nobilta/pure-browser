#!/usr/bin/env python3
"""Measure current Rust CSS queries on the bundled lists without historical Git refs.

This host microbenchmark excludes JNI copying, WebView/DOM work, networking,
startup and device thermals. Its timings are not page-load measurements.
"""
import hashlib
import json
from pathlib import Path
import platform
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'validation/results/cosmetic-benchmark'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    payloads = [ROOT / 'app/src/main/assets/filters' / name for name in
                ['easylist.txt', 'easyprivacy.txt', 'easylist-china.txt']]
    hosts = ['https://' + host + '/' for host in
             ['example.com', 'www.youtube.com', 'www.baidu.com', 'www.bilibili.com',
              'www.zhihu.com', 'www.wikipedia.org', 'www.reddit.com', 'www.google.com']]
    domains = set()
    for path in payloads:
        for line in path.read_text(encoding="utf-8").splitlines():
            if '##' not in line or line.startswith('!'):
                continue
            for domain in line.split('##', 1)[0].split(','):
                if re.fullmatch(r'[a-z0-9-]+(?:\.[a-z0-9-]+)+', domain):
                    domains.add(domain)
    for domain in sorted(domains):
        if len(hosts) >= 80:
            break
        host = 'https://' + domain + '/'
        if host not in hosts:
            hosts.append(host)
    assert len(hosts) == 80  # Exceeds the 32-host LRU: every cold-loop query misses.
    with tempfile.TemporaryDirectory(prefix='query-', dir=OUT) as scratch:
        output = Path(scratch)
        (output / 'hosts.txt').write_text('\n'.join(hosts) + '\n', encoding="utf-8")
        raw = subprocess.check_output(
            ['cargo', 'run', '--locked', '--release', '-p', 'adblock', '--example',
             'cosmetic_bench', '--', str(output), *map(str, payloads)],
            cwd=ROOT / 'rust', text=True, encoding="utf-8", timeout=300)
        result = {
            'host': platform.platform(),
            'rustc': subprocess.check_output(['rustc', '--version'], text=True, encoding="utf-8").strip(),
            'rust': json.loads(raw),
            'inputs': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in payloads},
            'outputSha256': {host: hashlib.sha256((output / f'rust-{i}.css').read_bytes()).hexdigest()
                             for i, host in enumerate(hosts)},
            'scope': 'Current host CSS query only; excludes JNI, DOM, page load and device effects',
        }
    assert result['rust']['coldSamples'] == 480 and result['rust']['warmSamples'] == 200
    (OUT / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != 'outputSha256'}, indent=2))


if __name__ == '__main__':
    main()
