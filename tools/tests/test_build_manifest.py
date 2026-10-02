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


if __name__ == "__main__":
    unittest.main()
