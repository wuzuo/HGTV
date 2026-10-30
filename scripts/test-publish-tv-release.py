#!/usr/bin/env python3
"""Offline regression checks; never contact release services."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-tv-release.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class PublishingTests(unittest.TestCase):
    def test_bundle_hash_and_notes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'app.apk').write_bytes(b'apk')
            manifest = dict(tag='kotlin-v0.1.3', packageName='io.github.wuzuo.hgtv', minSdk=23,
                            apk=dict(name='app.apk', size=3, sha256=hashlib.sha256(b'apk').hexdigest()), notes='notes')
            (root / 'update.json').write_text(json.dumps(manifest))
            (root / 'release-notes.md').write_text('notes\n')
            self.assertEqual(m.bundle(root)[0], 'kotlin-v0.1.3')
            (root / 'app.apk').write_bytes(b'bad')
            with self.assertRaises(m.Failure):
                m.bundle(root)

    def test_conflicting_asset_never_deleted(self):
        p = m.Publisher('test', 'kotlin-v0.1.3', 'notes')
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / 'update.json'
            file.write_bytes(b'abc')
            with patch.object(m, 'request') as network:
                with self.assertRaises(m.Failure):
                    p.upload({'assets': [dict(name=file.name, digest='sha256:wrong')]}, file)
                network.assert_not_called()

    def test_missing_tag_blocks_creation(self):
        p = m.Publisher('test', 'kotlin-v0.1.3', 'notes')
        with patch.object(p, 'api', side_effect=[None, [], None]) as api:
            with self.assertRaises(m.Failure):
                p.preflight()
            self.assertEqual(api.call_args.args[0], '/git/ref/tags/kotlin-v0.1.3')

    def test_preflight_failure_creates_no_releases(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with patch.object(m, 'bundle', return_value=('kotlin-v0.1.3', 'notes', [])), \
                 patch.object(m, 'credentials', return_value={'GITHUB_TOKEN': 'test'}), \
                 patch.object(m.Publisher, 'preflight', side_effect=m.Failure('unavailable')), \
                 patch.object(m.Publisher, 'stage') as stage, \
                 patch('sys.argv', ['publish', '--directory', str(root), '--publish']):
                with self.assertRaises(m.Failure):
                    m.main()
                stage.assert_not_called()

    def test_upload_targets_the_returned_upload_host_only(self):
        p = m.Publisher('secret', 'kotlin-v0.1.3', 'notes')
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / 'update.json'
            file.write_bytes(b'abc')
            release = dict(assets=[], upload_url='https://uploads.github.com/repos/o/r/releases/1/assets{?name,label}')
            with patch.object(m, 'request') as upload:
                p.upload(release, file)
                self.assertEqual(upload.call_args.args[0],
                                 'https://uploads.github.com/repos/o/r/releases/1/assets?name=update.json')
                self.assertNotIn('secret', upload.call_args.args[3])

    def test_unexpected_upload_host_is_rejected(self):
        p = m.Publisher('secret', 'kotlin-v0.1.3', 'notes')
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / 'update.json'
            file.write_bytes(b'abc')
            release = dict(assets=[], upload_url='https://evil.example.com/upload{?name}')
            with patch.object(m, 'request') as upload:
                with self.assertRaises(m.Failure):
                    p.upload(release, file)
                upload.assert_not_called()

    def test_github_draft_is_found_when_tag_endpoint_returns_404(self):
        p = m.Publisher('test', 'kotlin-v0.1.4', 'notes')
        draft = dict(id=42, tag_name='kotlin-v0.1.4', draft=True)
        with patch.object(p, 'api', side_effect=[None, [draft]]) as api:
            self.assertEqual(p.get(), draft)
            self.assertEqual(api.call_args.args[0], '/releases?per_page=100&page=1')

    def test_github_unrelated_draft_is_not_reused(self):
        p = m.Publisher('test', 'kotlin-v0.1.4', 'notes')
        with patch.object(p, 'api', side_effect=[None, [dict(tag_name='kotlin-v0.1.3', draft=True)]]):
            self.assertIsNone(p.get())

    def test_verify_missing_release_reports_retryable_failure(self):
        p = m.Publisher('test', 'kotlin-v0.1.4', 'notes')
        with patch.object(p, 'get', return_value=None):
            with self.assertRaises(m.Failure):
                p.verify([])

    def test_no_authenticated_redirect(self):
        self.assertIsNone(m.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example'))


if __name__ == '__main__':
    unittest.main()
