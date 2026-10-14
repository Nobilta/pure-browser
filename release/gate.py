#!/usr/bin/env python3
"""Release gates: refuse to draft a release whose commit, version or signer is unverified.

Only release/publish.sh calls this. Every gate either states what it checked or stops with the
reason; the two "previous release" gates are skipped while the repository has no published
release to compare with.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'release'))
from prepare import verified_signer  # noqa: E402


class GateError(Exception):
    pass


def gh(*arguments: str) -> str:
    result = subprocess.run([os.environ.get('GH') or 'gh', *arguments], cwd=ROOT, text=True, encoding='utf-8',
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise GateError('gh ' + ' '.join(arguments) + ' failed: ' + (result.stderr.strip() or 'unknown error'))
    return result.stdout


def gh_json(*arguments: str):
    return json.loads(gh(*arguments) or 'null')


def require_ci(repo: str, commit: str) -> None:
    """The commit must already be pushed and its CI run must have finished successfully."""
    runs = gh_json('run', 'list', '--repo', repo, '--commit', commit, '--branch', 'main',
                  '--workflow', 'ci.yml', '--limit', '50',
                  '--json', 'databaseId,headBranch,event,status,conclusion')
    ci = [run for run in runs if run.get('headBranch') == 'main'
          and run.get('event') in ('push', 'workflow_dispatch')]
    if not ci:
        raise GateError('no CI run for this commit yet: push it and wait for GitHub Actions')
    # A successful retry supersedes an older cancelled/failed run, but a newer pending or failed
    # run must still block the release. Do not accept a PR check in place of the main workflow.
    latest = max(ci, key=lambda run: run['databaseId'])
    if latest.get('status') != 'completed':
        raise GateError('latest CI has not finished for this commit (status: ' + str(latest.get('status')) + ')')
    if latest.get('conclusion') != 'success':
        raise GateError('latest CI did not succeed for this commit: ' + str(latest.get('conclusion')))


def previous_release(repo: str) -> str | None:
    releases = gh_json('release', 'list', '--repo', repo, '--exclude-drafts', '--exclude-pre-releases',
                       '--limit', '1', '--json', 'tagName')
    return releases[0]['tagName'] if releases else None


def require_version(source: Path, info: dict) -> None:
    """Compare version metadata; this alone does not prove the APK's source revision."""
    text = source.read_text(encoding='utf-8')
    code = re.search(r'versionCode\s*=\s*(\d+)', text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        raise GateError('cannot read versionCode/versionName from ' + str(source))
    if int(code.group(1)) != info['versionCode'] or name.group(1) != info['versionName']:
        raise GateError('APK is %s (%d) but the source declares %s (%s): rebuild before releasing'
                        % (info['versionName'], info['versionCode'], name.group(1), code.group(1)))


def require_newer(repo: str, tag: str | None, info: dict, workdir: Path) -> None:
    if tag is None:
        print('gate: no published release yet, skipping the version and signer comparisons')
        return
    gh('release', 'download', tag, '--repo', repo, '--pattern', 'update.json', '--dir', str(workdir), '--clobber')
    published = json.loads((workdir / 'update.json').read_text(encoding='utf-8'))
    if info['versionCode'] <= int(published['versionCode']):
        raise GateError('versionCode %d is not higher than the published %s'
                        % (info['versionCode'], published['versionCode']))


def require_same_signer(repo: str, tag: str | None, info: dict, workdir: Path) -> None:
    if tag is None:
        return
    gh('release', 'download', tag, '--repo', repo, '--pattern', '*.apk', '--dir', str(workdir), '--clobber')
    published_apks = sorted(workdir.glob('*.apk'))
    if not published_apks:
        raise GateError('published release ' + tag + ' has no APK to compare signatures with')
    if len(published_apks) != 1:
        raise GateError('expected one official APK in published release ' + tag)
    try:
        signer = verified_signer(published_apks[0])
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise GateError('cannot verify the published APK ' + published_apks[0].name) from error
    if signer != info['signerSha256']:
        raise GateError('signer %s differs from the published %s: an update could not be installed in place'
                        % (info['signerSha256'][:16], signer[:16]))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--info', type=Path, default=ROOT / 'outputs/release/package-info.json')
    parser.add_argument('--source', type=Path, default=ROOT / 'app/build.gradle.kts')
    args = parser.parse_args()

    info = json.loads(args.info.read_text(encoding='utf-8'))
    if Path(info['apk']).resolve() != args.apk.resolve():
        raise GateError('the prepared attachments are for %s, not %s' % (info['apk'], args.apk))
    with tempfile.TemporaryDirectory() as directory:
        workdir = Path(directory)
        tag = previous_release(args.repo)
        require_ci(args.repo, args.commit)
        print('gate: CI succeeded for ' + args.commit[:12])
        require_version(args.source, info)
        print('gate: APK %s (%d) matches the source' % (info['versionName'], info['versionCode']))
        require_newer(args.repo, tag, info, workdir)
        require_same_signer(args.repo, tag, info, workdir)
        if tag is not None:
            print('gate: newer than %s and signed by the same certificate' % tag)


if __name__ == '__main__':
    try:
        main()
    except GateError as error:
        sys.exit('Release gate failed: ' + str(error))
