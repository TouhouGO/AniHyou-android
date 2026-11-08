"""Bounded retries for transient bootstrap network errors."""
import time
import urllib.error

TRANSIENT_HTTP = {429, 502, 503, 504}


def describe(error):
    if isinstance(error, urllib.error.HTTPError):
        return f"HTTPError status {error.code}"
    return type(error).__name__


def transient(error):
    if isinstance(error, urllib.error.HTTPError):
        return error.code in TRANSIENT_HTTP
    if isinstance(error, urllib.error.URLError):
        return isinstance(error.reason, (TimeoutError, ConnectionResetError))
    return isinstance(error, (TimeoutError, ConnectionResetError))


def run(operation, label, sleep=None):
    if sleep is None:
        sleep = time.sleep
    for attempt in range(1, 4):
        try:
            return operation()
        except OSError as error:
            retry = transient(error) and attempt < 3
            print(f"{label}: {describe(error)} (attempt {attempt}/3; "
                  f"{'retrying' if retry else 'stopping'})", flush=True)
            if not retry:
                raise
            sleep(attempt)
