import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("twin_voice", Path(__file__).parents[1] / "voice" / "twin_voice.py")
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


class TwinVoiceTest(unittest.TestCase):
    def test_only_permissive_engines(self):
        self.assertEqual("MIT", v.check_engine("chatterbox")["licence"])
        self.assertEqual("MIT", v.check_engine("OpenVoice")["licence"])
        for bad in ("f5-tts", "XTTS", "coqui", "zipvoice"):
            with self.assertRaises(v.VoiceError):
                v.check_engine(bad)
        with self.assertRaises(v.VoiceError):
            v.check_engine("something-else")

    def test_reference_takes_are_clean_and_capped(self):
        with tempfile.TemporaryDirectory() as d:
            audio = os.path.join(d, "audio")
            os.makedirs(audio)
            records = []
            for i, ms in enumerate([3_000, 8_000, 29_000, 31_000, 20_000] + [25_000] * 8):
                name = "a%02d" % i
                Path(audio, name + ".flac").write_bytes(b"fLaC")
                records.append({"id": i, "kind": "answer", "body": {"transcript": "words %d" % i, "audio": [{"file": name, "ms": ms}]}})
            records.append({"id": 99, "kind": "answer", "body": {"transcript": "", "audio": [{"file": "a01", "ms": 9_000}]}})
            records.append({"id": 98, "kind": "event", "body": {"audio": [{"file": "a02", "ms": 9_000}]}})
            takes = v.pick_references(records, audio)
            self.assertTrue(all(v.MIN_MS <= t["ms"] <= v.MAX_MS for t in takes))
            self.assertLessEqual(sum(t["ms"] for t in takes), v.TOTAL_MS)
            self.assertEqual(29_000, takes[0]["ms"])
            self.assertNotIn(99, [t["journal_record_id"] for t in takes])

    def test_prepare_and_sidecar(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "x", "journal"))
            os.makedirs(os.path.join(d, "x", "audio"))
            Path(d, "x", "audio", "t1.flac").write_bytes(b"fLaC")
            Path(d, "x", "journal", "records.jsonl").write_text(json.dumps(
                {"id": 7, "kind": "answer", "body": {"transcript": "hello kids", "audio": [{"file": "t1", "ms": 10_000}]}}) + "\n")
            try:
                ref = v.prepare(os.path.join(d, "x"), os.path.join(d, "w"))
            except Exception as e:  # soundfile may be installed but can't read the fake FLAC
                self.skipTest("soundfile present: %s" % e)
            self.assertEqual(1, len(ref["takes"]))
            s = v.sidecar("chatterbox", "hi", ref)
            self.assertTrue(s["synthetic"])
            self.assertIn("not Dad", s["label"])
            self.assertEqual([7], s["reference_takes"])
            self.assertEqual("Perth (Resemble AI)", s["watermark"])
            with self.assertRaises(v.VoiceError):
                v.prepare(d, os.path.join(d, "w2"))

    def test_no_network_code(self):
        src = (Path(__file__).parents[1] / "voice" / "twin_voice.py").read_text()
        for banned in ("urllib", "requests", "http.client", "socket", "upload"):
            self.assertNotIn("import " + banned, src)


if __name__ == "__main__":
    unittest.main()
