import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_gradle as module


class PrepareTest(unittest.TestCase):
    def test_wrapper_version_change_requires_new_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gradle-wrapper.properties"
            path.write_text("distributionUrl=https\\://services.gradle.org/distributions/gradle-other-bin.zip\n")
            with self.assertRaisesRegex(ValueError, "together"):
                module.check_wrapper(path)
            path.write_text("distributionUrl=" + module.URL.replace(":", "\\:") + "\n")
            module.check_wrapper(path)

    def test_wrapper_cache_path(self):
        self.assertEqual(module.cache_dir('/tmp').name, '8wepis63pzuhrlfaiy04bagwu')

    def test_wrong_release_asset_rejected(self):
        metadata = {"tag_name": "v9.8.2", "assets": [{"name": "wrong.zip", "id": 1, "size": module.SIZE}]}
        with patch.object(module, "request", return_value=io.BytesIO(json.dumps(metadata).encode())):
            with self.assertRaises(ValueError):
                module.asset_url()

    def test_hash_mismatch_does_not_activate(self):
        with tempfile.TemporaryDirectory() as home, patch.object(module, "SIZE", 3), \
                patch.object(module, "SHA256", "0" * 64), \
                patch.object(module, "request", return_value=io.BytesIO(b"bad")), \
                patch.object(module, "asset_url", return_value="https://example.invalid"):
            with self.assertRaises(ValueError):
                module.prepare(home)
            self.assertFalse((module.cache_dir(home) / module.NAME).exists())

    def test_verified_cache_reused(self):
        with tempfile.TemporaryDirectory() as home, patch.object(module, "SIZE", 3), \
                patch.object(module, "SHA256", hashlib.sha256(b"zip").hexdigest()), \
                patch.object(module, "request") as request:
            directory = module.cache_dir(home)
            directory.mkdir(parents=True)
            (directory / module.NAME).write_bytes(b"zip")
            module.prepare(home)
            request.assert_not_called()

    def test_fallback_activates_only_verified_archive(self):
        with tempfile.TemporaryDirectory() as home, patch.object(module, "SIZE", 3), \
                patch.object(module, "SHA256", hashlib.sha256(b"zip").hexdigest()), \
                patch.object(module, "request", side_effect=[OSError(), io.BytesIO(b"zip")]), \
                patch.object(module, "asset_url", return_value="https://example.invalid"):
            module.prepare(home)
            self.assertEqual((module.cache_dir(home) / module.NAME).read_bytes(), b"zip")


if __name__ == '__main__':
    unittest.main()
