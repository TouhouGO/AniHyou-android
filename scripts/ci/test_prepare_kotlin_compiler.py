import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_kotlin_compiler as module


class CompilerTest(unittest.TestCase):
    def test_expected_cache_path(self):
        self.assertEqual(str(module.destination('/tmp')), '/tmp/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.10/cac44a5a360313427c693a9de295017bc76d6e4e/kotlin-compiler-embeddable-2.4.10.jar')

    def test_wrong_hash_not_activated(self):
        with tempfile.TemporaryDirectory() as home, patch.object(module, 'SIZE', 3), \
                patch.object(module.urllib.request, 'urlopen', return_value=io.BytesIO(b'bad')):
            with self.assertRaises(ValueError):
                module.prepare(home)
            self.assertFalse(module.destination(home).exists())

    def test_verified_cache_reuse(self):
        with tempfile.TemporaryDirectory() as home, patch.object(module, 'SIZE', 3), \
                patch.object(module, 'SHA1', hashlib.sha1(b'jar').hexdigest()), \
                patch.object(module, 'SHA256', hashlib.sha256(b'jar').hexdigest()), \
                patch.object(module.urllib.request, 'urlopen') as request:
            path = module.destination(home)
            path.parent.mkdir(parents=True)
            path.write_bytes(b'jar')
            module.prepare(home)
            request.assert_not_called()

    def test_verified_download_activated(self):
        with tempfile.TemporaryDirectory() as home, patch.object(module, 'SIZE', 3), \
                patch.object(module, 'SHA1', hashlib.sha1(b'jar').hexdigest()), \
                patch.object(module, 'SHA256', hashlib.sha256(b'jar').hexdigest()), \
                patch.object(module.urllib.request, 'urlopen', return_value=io.BytesIO(b'jar')):
            module.prepare(home)
            self.assertEqual(module.destination(home).read_bytes(), b'jar')

    def test_wrapper_guard_runs_before_cache_reuse(self):
        with tempfile.TemporaryDirectory() as home, \
                patch.object(module, 'check_wrapper', side_effect=ValueError("changed wrapper")), \
                patch.object(module.urllib.request, 'urlopen') as request:
            with self.assertRaises(ValueError):
                module.prepare(home)
            request.assert_not_called()
            self.assertFalse(module.destination(home).exists())


class DownloadRetryTest(unittest.TestCase):
    def test_body_timeout_discards_partial_and_recovers(self):
        class Interrupted(io.BytesIO):
            def read(self, size=-1):
                if self.tell():
                    raise TimeoutError('private URL')
                return super().read(1)

        with tempfile.TemporaryDirectory() as home, patch.object(module, 'SIZE', 3), \
                patch.object(module, 'SHA1', hashlib.sha1(b'jar').hexdigest()), \
                patch.object(module, 'SHA256', hashlib.sha256(b'jar').hexdigest()), \
                patch.object(module.urllib.request, 'urlopen', side_effect=[Interrupted(b'bad'), io.BytesIO(b'jar')]) as request, \
                patch('network_retry.time.sleep'):
            module.prepare(home)
            self.assertEqual(request.call_count, 2)
            self.assertEqual(module.destination(home).read_bytes(), b'jar')

    def test_bad_digest_after_timeout_never_activates(self):
        class Interrupted(io.BytesIO):
            def read(self, size=-1):
                raise TimeoutError()

        with tempfile.TemporaryDirectory() as home, patch.object(module, 'SIZE', 3), \
                patch.object(module.urllib.request, 'urlopen', side_effect=[Interrupted(), io.BytesIO(b'bad')]) as request, \
                patch('network_retry.time.sleep'):
            with self.assertRaises(ValueError):
                module.prepare(home)
            self.assertEqual(request.call_count, 2)
            self.assertFalse(module.destination(home).exists())


if __name__ == '__main__':
    unittest.main()
