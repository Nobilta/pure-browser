"""Release failures must be caught before a draft or its attachments are uploaded."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import gate
import prepare


class ReleaseGateTest(unittest.TestCase):
    def ci_run(self, identifier, conclusion='success', status='completed', **extra):
        return dict(databaseId=identifier, headBranch='main', event='push',
                    conclusion=conclusion, status=status, **extra)

    def check_ci(self, runs):
        with patch.object(gate, 'gh_json', return_value=runs) as request:
            gate.require_ci('owner/repo', 'commit')
        arguments = request.call_args.args
        self.assertIn('ci.yml', arguments)
        self.assertIn('main', arguments)
        self.assertIn('commit', arguments)

    def test_success_supersedes_older_failure_or_cancellation(self):
        for conclusion in ('failure', 'cancelled'):
            with self.subTest(conclusion=conclusion):
                self.check_ci([self.ci_run(1, conclusion), self.ci_run(2)])

    def test_newer_pending_or_failed_run_blocks_older_success(self):
        for latest in (self.ci_run(2, 'failure'), self.ci_run(2, None, 'in_progress')):
            with self.subTest(latest=latest), self.assertRaises(gate.GateError):
                self.check_ci([latest, self.ci_run(1)])

    def test_missing_ci_blocks_release(self):
        with self.assertRaises(gate.GateError):
            self.check_ci([])

    def test_pr_or_other_branch_cannot_substitute_for_main_ci(self):
        for change in ({'event': 'pull_request'}, {'headBranch': 'feature'}):
            run = self.ci_run(1)
            run.update(change)
            with self.subTest(change=change), self.assertRaises(gate.GateError):
                self.check_ci([run])

    def test_manual_main_ci_is_accepted(self):
        run = self.ci_run(1)
        run['event'] = 'workflow_dispatch'
        self.check_ci([run])

    def test_gh_override_is_used_by_all_gates(self):
        with patch.dict(os.environ, {'GH': '/custom/gh'}), patch.object(gate.subprocess, 'run') as run:
            run.return_value = subprocess.CompletedProcess([], 0, '[]', '')
            self.assertEqual(gate.gh_json('release', 'list'), [])
            self.assertEqual(run.call_args.args[0][0], '/custom/gh')

    def test_source_version_mismatch_requires_rebuild(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'build.gradle.kts'
            source.write_text('versionCode = 42\nversionName = "0.14.1"', encoding='utf-8')
            gate.require_version(source, {'versionName': '0.14.1', 'versionCode': 42})
            with self.assertRaises(gate.GateError):
                gate.require_version(source, {'versionName': '0.14.0', 'versionCode': 41})

    def test_same_or_older_version_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(gate, 'gh'):
            workdir = Path(directory)
            (workdir / 'update.json').write_text('{"versionCode": 41}', encoding='utf-8')
            for code in (40, 41):
                with self.subTest(code=code), self.assertRaises(gate.GateError):
                    gate.require_newer('owner/repo', 'v0.14.0', {'versionCode': code}, workdir)
            gate.require_newer('owner/repo', 'v0.14.0', {'versionCode': 42}, workdir)

    def test_invalid_published_apk_blocks_signer_comparison(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(gate, 'gh'):
            workdir = Path(directory)
            (workdir / 'published.apk').touch()
            with patch.object(gate, 'verified_signer', side_effect=subprocess.CalledProcessError(1, 'apksigner')):
                with self.assertRaisesRegex(gate.GateError, 'cannot verify'):
                    gate.require_same_signer('owner/repo', 'v0.14.0', {'signerSha256': 'a' * 64}, workdir)

    def test_changed_signer_blocks_upgrade(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(gate, 'gh'):
            workdir = Path(directory)
            (workdir / 'published.apk').touch()
            with patch.object(gate, 'verified_signer', return_value='b' * 64):
                with self.assertRaisesRegex(gate.GateError, 'differs'):
                    gate.require_same_signer('owner/repo', 'v0.14.0', {'signerSha256': 'a' * 64}, workdir)


class ReleasePreparationTest(unittest.TestCase):
    certificate = ('Verified using v2 scheme (APK Signature Scheme v2): true\n'
                   'Number of signers: 1\nSigner #1 certificate SHA-256 digest: ' + 'A' * 64 + '\n')

    def test_valid_single_signer_is_normalized(self):
        with patch.object(prepare, 'android_tool', return_value='apksigner'), patch.object(prepare, 'run', return_value=self.certificate):
            self.assertEqual(prepare.verified_signer(Path('release.apk')), 'a' * 64)

    def test_failed_verification_cannot_return_a_printed_certificate(self):
        failure = subprocess.CalledProcessError(1, 'apksigner', output=self.certificate)
        with patch.object(prepare, 'android_tool', return_value='apksigner'), patch.object(prepare, 'run', side_effect=failure):
            with self.assertRaises(subprocess.CalledProcessError):
                prepare.verified_signer(Path('tampered.apk'))

    def test_v1_only_or_multiple_signers_are_rejected(self):
        for certificate in (self.certificate.replace(': true', ': false'),
                            self.certificate.replace('Number of signers: 1', 'Number of signers: 2')):
            with self.subTest(certificate=certificate), patch.object(prepare, 'android_tool', return_value='apksigner'):
                with patch.object(prepare, 'run', return_value=certificate), self.assertRaises(ValueError):
                    prepare.verified_signer(Path('release.apk'))

    def test_verification_record_requires_exact_nonempty_version(self):
        with tempfile.TemporaryDirectory() as directory:
            notes = Path(directory) / 'notes.md'
            for text in ('', ' \n', '# 0.14.10', '# 0.14.1.1', '# 0.14.1-beta', '# 10.14.1'):
                notes.write_text(text, encoding='utf-8')
                with self.subTest(text=text), self.assertRaises(ValueError):
                    prepare.verify_notes('0.14.1', notes)
            for text in ('# 0.14.1 验证记录', '# Pure Browser v0.14.1\nDevice: API 37'):
                notes.write_text(text, encoding='utf-8')
                prepare.verify_notes('0.14.1', notes)


if __name__ == '__main__':
    unittest.main()
