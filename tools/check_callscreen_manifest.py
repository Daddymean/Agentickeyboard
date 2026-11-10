#!/usr/bin/env python3
"""Fail closed on permission, component and backup drift in merged APK manifests."""
import pathlib
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
ALLOWED = {"android.permission.READ_CONTACTS", "android.permission.POST_NOTIFICATIONS"}

def violations(root):
    errors = []
    permissions = {node.get(A + "name") for node in root if node.tag.startswith("uses-permission")}
    if permissions != ALLOWED:
        errors.append("permission allowlist mismatch")
    app = root.find("application")
    if app is None:
        return errors + ["application missing"]
    for attribute, expected in (("allowBackup", "false"), ("fullBackupContent", "false"), ("usesCleartextTraffic", "false")):
        if app.get(A + attribute) != expected:
            errors.append(attribute + " must be " + expected)
    if app.get(A + "dataExtractionRules") != "@xml/data_extraction_rules":
        errors.append("backup exclusion rules missing")
    services = app.findall("service")
    if len(services) != 1:
        errors.append("exactly one screening service required")
    else:
        service = services[0]
        if service.get(A + "permission") != "android.permission.BIND_SCREENING_SERVICE":
            errors.append("screening service binding permission missing")
        if service.get(A + "exported") != "true":
            errors.append("screening service must be available to Telecom")
        actions = service.findall("intent-filter/action")
        if {a.get(A + "name") for a in actions} != {"android.telecom.CallScreeningService"}:
            errors.append("unexpected service intents")
    if any(app.findall(tag) for tag in ("provider", "receiver", "activity-alias")):
        errors.append("unexpected background/exported component")
    return errors

def main(paths):
    if not paths:
        raise SystemExit("pass at least one merged manifest XML path")
    for path in paths:
        errors = violations(ET.parse(path).getroot())
        if errors:
            raise SystemExit(str(path) + ": " + "; ".join(errors))
    print("Callscreen manifest privacy guard passed:", len(paths), "manifest(s)")

if __name__ == "__main__":
    main(sys.argv[1:])
