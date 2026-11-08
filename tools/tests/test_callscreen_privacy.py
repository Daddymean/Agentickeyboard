import pathlib
import unittest
import importlib.util
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
A = "{http://schemas.android.com/apk/res/android}"

class CallscreenPrivacyTest(unittest.TestCase):
    def test_standalone_manifest_is_local_only(self):
        path = ROOT / "callscreen/src/main/AndroidManifest.xml"
        self.assertTrue(path.exists(), "standalone callscreen manifest missing")
        root = ET.parse(path).getroot()
        permissions = {p.get(A + "name") for p in root.findall("uses-permission")}
        self.assertEqual(permissions, {"android.permission.READ_CONTACTS", "android.permission.POST_NOTIFICATIONS"})
        app = root.find("application")
        self.assertEqual(app.get(A + "allowBackup"), "false")
        self.assertEqual(app.get(A + "usesCleartextTraffic"), "false")
        service = app.find("service")
        self.assertEqual(service.get(A + "permission"), "android.permission.BIND_SCREENING_SERVICE")
        self.assertEqual(service.get(A + "exported"), "true")

    def test_module_has_no_project_dependencies(self):
        build = ROOT / "callscreen/build.gradle.kts"
        self.assertTrue(build.exists(), "standalone module missing")
        self.assertNotIn("project(", build.read_text())
        self.assertIn('include(":callscreen")', (ROOT / "settings.gradle.kts").read_text())

    def test_no_history_or_sensitive_logging(self):
        sources = list((ROOT / "callscreen/src/main").rglob("*.kt"))
        self.assertTrue(sources, "screening implementation missing")
        for source in sources:
            text = source.read_text()
            for forbidden in ("android.util.Log", "println(", "java.net", "okhttp", "RoomDatabase", "FileOutputStream", "RECORD_AUDIO"):
                self.assertNotIn(forbidden, text, str(source))

class MergedManifestGuardTest(unittest.TestCase):
    def load_guard(self):
        path = ROOT / "tools/check_callscreen_manifest.py"
        self.assertTrue(path.exists(), "merged manifest guard missing")
        spec = importlib.util.spec_from_file_location("callscreen_guard", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_accepts_local_manifest(self):
        guard = self.load_guard()
        root = ET.parse(ROOT / "callscreen/src/main/AndroidManifest.xml").getroot()
        self.assertEqual([], guard.violations(root))

    def test_rejects_injected_network_permission(self):
        guard = self.load_guard()
        root = ET.parse(ROOT / "callscreen/src/main/AndroidManifest.xml").getroot()
        ET.SubElement(root, "uses-permission", {A + "name": "android.permission.INTERNET"})
        self.assertTrue(guard.violations(root))

    def test_rejects_backup_and_unguarded_service(self):
        guard = self.load_guard()
        root = ET.parse(ROOT / "callscreen/src/main/AndroidManifest.xml").getroot()
        app = root.find("application")
        app.set(A + "allowBackup", "true")
        app.find("service").attrib.pop(A + "permission")
        self.assertGreaterEqual(len(guard.violations(root)), 2)

class DeadlineIsolationTest(unittest.TestCase):
    def test_notifications_and_cancellation_are_not_on_response_thread(self):
        source = (ROOT / "callscreen/src/main/java/io/github/daddymean/callscreen/ScreeningService.kt").read_text()
        self.assertIn("effects.execute", source, "notification/provider binder calls must not stall the watchdog")
        response = source.index("respondToCall(details, response)")
        effect = source.index("effects.execute", response)
        self.assertGreater(source.index("cancellation.cancel()", effect), response)

if __name__ == "__main__":
    unittest.main()
