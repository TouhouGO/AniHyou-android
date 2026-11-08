import io
import urllib.error
import unittest
from unittest.mock import Mock

import network_retry as retry


def http(code):
    return urllib.error.HTTPError('https://example.invalid/private', code, 'error', {}, None)


class NetworkRetryTest(unittest.TestCase):
    def test_transient_failures_are_bounded(self):
        for error in (http(429), http(502), http(503), http(504),
                      urllib.error.URLError(TimeoutError()), ConnectionResetError()):
            with self.subTest(error=type(error).__name__):
                operation = Mock(side_effect=error)
                sleep = Mock()
                with self.assertRaises(type(error)):
                    retry.run(operation, 'test', sleep)
                self.assertEqual(operation.call_count, 3)
                self.assertEqual(sleep.call_count, 2)

    def test_permanent_http_does_not_retry(self):
        for code in (403, 404):
            operation, sleep = Mock(side_effect=http(code)), Mock()
            with self.assertRaises(urllib.error.HTTPError):
                retry.run(operation, 'test', sleep)
            self.assertEqual(operation.call_count, 1)
            sleep.assert_not_called()

    def test_value_error_does_not_retry(self):
        operation, sleep = Mock(side_effect=ValueError('bad digest')), Mock()
        with self.assertRaises(ValueError):
            retry.run(operation, 'test', sleep)
        self.assertEqual(operation.call_count, 1)
        sleep.assert_not_called()

    def test_status_logging_does_not_print_url(self):
        output = io.StringIO()
        from contextlib import redirect_stdout
        with redirect_stdout(output), self.assertRaises(urllib.error.HTTPError):
            retry.run(Mock(side_effect=http(403)), 'test', Mock())
        self.assertIn('HTTPError status 403', output.getvalue())
        self.assertNotIn('example.invalid', output.getvalue())
