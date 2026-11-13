import unittest

from release_version import release_version


class ReleaseVersionTest(unittest.TestCase):
    def test_release_versions_are_monotonic_at_rollovers(self):
        tags = ["0.0.1", "1.0.0", "1.0.99", "1.1.0", "1.99.99", "2.0.0"]
        codes = [release_version(tag)[1] for tag in tags]
        self.assertEqual([1, 10000, 10099, 10100, 19999, 20000], codes)
        self.assertEqual(sorted(codes), codes)
        self.assertEqual(("1.2.3", 10203), release_version("1.2.3"))

    def test_rejects_invalid_or_ambiguous_tags(self):
        for tag in [
            "v1.0.0", "1.0", "1.0.0-beta", "01.0.0", "1.00.0", "1.0.01",
            "1.100.0", "1.0.100", "0.0.0", "210000.0.1", "99999999999999999999.0.0",
            "-1.0.0", "1.0.0\n", "1.0.0; echo bad",
        ]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release_version(tag)

    def test_android_version_code_upper_boundary(self):
        self.assertEqual(("210000.0.0", 2100000000), release_version("210000.0.0"))


if __name__ == "__main__":
    unittest.main()
