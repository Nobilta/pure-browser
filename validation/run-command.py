#!/usr/bin/env python3
"""Run a build/check with durable logs and completion metadata across UI reconnections."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--result', type=Path, required=True)
parser.add_argument('command', nargs=argparse.REMAINDER)
args = parser.parse_args()
command = args.command[1:] if args.command[:1] == ['--'] else args.command
if not command:
    parser.error('a command is required')
args.result.parent.mkdir(parents=True, exist_ok=True)
start = time.monotonic()
record = {'pid': os.getpid(), 'state': 'running', 'startedAt': time.strftime('%Y-%m-%d %H:%M:%S')}
args.result.write_text(json.dumps(record, indent=2))
with args.result.with_suffix('.log').open('w') as log:
    try:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        record.update(state='completed', exitCode=result.returncode, seconds=round(time.monotonic()-start, 2))
    except Exception as error:
        record.update(state='failed', error=str(error))
args.result.write_text(json.dumps(record, indent=2))
