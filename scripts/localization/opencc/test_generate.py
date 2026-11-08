import unittest
from generate import canonicalize, generate, SOURCE


class GenerateTest(unittest.TestCase):
    def test_chain_and_cycle(self):
        self.assertEqual({1: 3, 2: 3}, canonicalize({1: 2, 2: 3}))
        with self.assertRaisesRegex(ValueError, 'cycle'):
            canonicalize({1: 2, 2: 1})
        self.assertEqual({1: 1}, canonicalize({1: 1}))

    def test_source_is_pinned(self):
        self.assertEqual(4 + 4113 * 8, len(generate(SOURCE.read_bytes())))
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            generate(b'modified')


if __name__ == '__main__':
    unittest.main()
