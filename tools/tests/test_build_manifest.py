import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("build_manifest", Path(__file__).parents[1] / "build_manifest.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class ManifestTest(unittest.TestCase):
    def test_hashes_exact_apk_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-debug.apk"
            apk.write_bytes(b"apk\x00\xff")
            result = m.manifest(apk, "a" * 40, "https://github.com/Daddymean/Agentickeyboard/actions/runs/123")
            self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), result["sha256"])
            self.assertEqual(5, result["bytes"])
            self.assertEqual("app-debug.apk", result["apk"])

    def test_rejects_ambiguous_commit(self):
        with self.assertRaises(ValueError):
            m.manifest(Path("missing.apk"), "main", "https://github.com/Daddymean/Agentickeyboard/actions/runs/123")

    def test_rejects_unrelated_run(self):
        with self.assertRaises(ValueError):
            m.manifest(Path("missing.apk"), "a" * 40, "https://example.com/123")

    def test_rejects_lookalike_run_host(self):
        with self.assertRaises(ValueError):
            m.manifest(Path("missing.apk"), "a" * 40, "https://githubXcom/Daddymean/Agentickeyboard/actions/runs/123")

    def test_pr_records_actual_built_sha_and_reproducible_parents(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-debug.apk"
            apk.write_bytes(b"test")
            result = m.manifest(apk, "a" * 40, "https://github.com/Daddymean/Agentickeyboard/actions/runs/123", "b" * 40, "c" * 40, "pull_request")
            self.assertEqual("a" * 40, result["commit"])
            self.assertEqual("a" * 40, result["built_sha"])
            self.assertEqual("b" * 40, result["head_sha"])
            self.assertEqual("c" * 40, result["base_sha"])

    def test_pr_requires_both_parents(self):
        with self.assertRaises(ValueError):
            m.manifest(Path("missing.apk"), "a" * 40, "https://github.com/Daddymean/Agentickeyboard/actions/runs/123", event="pull_request")


if __name__ == "__main__":
    unittest.main()
