import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("gradle-with-network-retry.sh").resolve()


class RetryTest(unittest.TestCase):
    def run_fixture(self, outputs):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "gradlew").write_text("""#!/usr/bin/env python3
import json
from pathlib import Path
import sys
p = Path('calls')
n = int(p.read_text()) if p.exists() else 0
p.write_text(str(n + 1))
outputs = json.loads(Path('outputs.json').read_text())
code, text = outputs[min(n, len(outputs) - 1)]
print(text)
Path('args').write_text(repr(sys.argv[1:]))
sys.exit(code)
""")
            (root / "gradlew").chmod(0o755)
            import json
            (root / "outputs.json").write_text(json.dumps(outputs))
            result = subprocess.run([str(SCRIPT), "test", "--stacktrace"], cwd=root,
                                    text=True, capture_output=True, env=os.environ.copy())
            return result, int((root / "calls").read_text()), (root / "args").read_text()

    def test_recovers_from_dependency_504(self):
        network = "Could not download dependency\nReceived status code 504 from server"
        result, calls, args = self.run_fixture([(1, network), (0, "BUILD SUCCESSFUL")])
        self.assertEqual((result.returncode, calls), (0, 2))
        self.assertIn("BUILD SUCCESSFUL", result.stdout)
        self.assertEqual(args, "['test', '--stacktrace']")

    def test_distribution_timeout_is_bounded(self):
        network = "Downloading https://services.gradle.org/distributions/gradle.zip\nRead timed out"
        result, calls, _ = self.run_fixture([(7, network)])
        self.assertEqual((result.returncode, calls), (7, 3))

    def test_dependency_read_timeout_is_bounded(self):
        output = "Could not download kotlin-compiler-embeddable.jar\nCould not GET 'https://example.org/file.jar'\nRead timed out"
        result, calls, _ = self.run_fixture([(7, output)])
        self.assertEqual((result.returncode, calls), (7, 3))

    def test_unrelated_timeout_does_not_retry(self):
        result, calls, _ = self.run_fixture([(1, "Task timed out\nRead timed out")])
        self.assertEqual((result.returncode, calls), (1, 1))

    def test_test_failure_does_not_retry_even_with_network_error(self):
        output = "Could not download dependency\nReceived status code 503 from server\nThere were failing tests"
        result, calls, _ = self.run_fixture([(1, output)])
        self.assertEqual((result.returncode, calls), (1, 1))

    def test_success_is_not_retried(self):
        result, calls, _ = self.run_fixture([(0, "BUILD SUCCESSFUL")])
        self.assertEqual((result.returncode, calls), (0, 1))

    def test_permanent_http_failure_is_not_retried(self):
        output = "Could not download dependency\nReceived status code 403 from server"
        result, calls, _ = self.run_fixture([(1, output)])
        self.assertEqual((result.returncode, calls), (1, 1))


if __name__ == "__main__":
    unittest.main()
