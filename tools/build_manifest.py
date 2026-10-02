#!/usr/bin/env python3
"""Describe an APK without needing Android tooling or reading credentials."""
import argparse
import hashlib
import json
import re
from pathlib import Path


def manifest(apk, commit, run_url):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("commit must be a full lowercase Git SHA")
    if not re.fullmatch(r"https://github.com/Daddymean/Agentickeyboard/actions/runs/[0-9]+", run_url):
        raise ValueError("run_url must identify an Agentickeyboard Actions run")
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"schema_version": 1, "repository": "Daddymean/Agentickeyboard",
            "commit": commit, "run_url": run_url, "variant": "debug",
            "apk": apk.name, "sha256": digest.hexdigest(), "bytes": apk.stat().st_size}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = manifest(args.apk, args.commit, args.run_url)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
