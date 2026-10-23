#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Keith's voice for the legacy twin, on a computer (KEYBOARD-038).

Phase 1 is already done on the phone: every recording is kept as raw, lossless FLAC, and that
is the most authentic voice the kids will have. This tool is phase 2. It runs on Keith's own
computer, against an export he decrypted with decrypt_twin_export.py:

    python3 twin_voice.py prepare twin-data/ voice-work/
        Picks clean reference takes (journal recordings with a transcript, 6 to 30 s each,
        up to 3 minutes in total) and writes voice-work/reference.json. With soundfile
        installed, it also writes voice-work/reference.wav.
    python3 twin_voice.py speak voice-work/ "Text to say" out.wav [--engine chatterbox|openvoice]
        Synthesises speech in Keith's voice and writes out.wav plus out.wav.json. The
        sidecar always marks the audio as synthetic, carries the AI label, and records the
        engine and its licence.

Engines, all permissively licensed:
  chatterbox  Resemble AI Chatterbox (MIT; `pip install chatterbox-tts`). Its output is
              watermarked with Perth, which marks it as synthetic. This is the default.
  openvoice   MyShell OpenVoice V2 (MIT since April 2024): MeloTTS base speech plus the
              tone-colour converter. It needs the OpenVoice repo and checkpoints installed.
Refused: F5-TTS (CC-BY-NC weights), XTTS / Coqui (CPML, non-commercial), and ZipVoice
trained on Emilia (CC-BY-NC data).

This script has no network code. The engines download their weights from Hugging Face the
first time they run. After that, set HF_HUB_OFFLINE=1. No audio is ever uploaded. The use
rules in RELEASE.md apply to anything this makes: it is always presented as an AI, and
never published.
"""
import argparse
import json
import os
import sys

LABEL = "AI twin built from Dad's own words. This is not Dad."
ALLOWED = {
    "chatterbox": {"licence": "MIT", "project": "resemble-ai/chatterbox", "watermark": "Perth (Resemble AI)"},
    "openvoice": {"licence": "MIT", "project": "myshell-ai/OpenVoice (V2)", "watermark": None},
}
REFUSED = {
    "f5-tts": "F5-TTS weights are CC-BY-NC (non-commercial)",
    "f5": "F5-TTS weights are CC-BY-NC (non-commercial)",
    "xtts": "XTTS v2 is under the Coqui Public Model License (non-commercial)",
    "coqui": "Coqui XTTS is under the CPML (non-commercial)",
    "zipvoice": "ZipVoice's public weights are trained on Emilia (CC-BY-NC)",
}
MIN_MS, MAX_MS, TOTAL_MS = 6_000, 30_000, 180_000


class VoiceError(Exception):
    pass


def check_engine(name):
    key = name.lower()
    if key in REFUSED:
        raise VoiceError("refused: " + REFUSED[key])
    if key not in ALLOWED:
        raise VoiceError("unknown engine %r (allowed: %s)" % (name, ", ".join(sorted(ALLOWED))))
    return ALLOWED[key]


def pick_references(records, audio_dir):
    """Chooses reference takes from journal answers: recordings of 6–30 s with a transcript,
    longest first, up to 3 minutes. Returns a list of dicts (file, ms, transcript, record id)."""
    takes = []
    for r in records:
        if r.get("kind") != "answer":
            continue
        body = r.get("body", {})
        transcript = (body.get("transcript") or "").strip()
        for a in body.get("audio") or []:
            ms = int(a.get("ms") or 0)
            f = os.path.basename(str(a.get("file") or ""))
            if not f or not (MIN_MS <= ms <= MAX_MS) or not transcript:
                continue
            path = os.path.join(audio_dir, f + ".flac")
            if os.path.exists(path):
                takes.append({"file": path, "ms": ms, "transcript": transcript, "journal_record_id": r.get("id")})
    takes.sort(key=lambda t: -t["ms"])
    out, total = [], 0
    for t in takes:
        if total + t["ms"] > TOTAL_MS:
            continue
        out.append(t)
        total += t["ms"]
    return out


def sidecar(engine, text, reference):
    info = check_engine(engine)
    return {
        "synthetic": True,
        "label": LABEL,
        "engine": engine,
        "licence": info["licence"],
        "project": info["project"],
        "watermark": info["watermark"],
        "text": text,
        "reference_takes": [t["journal_record_id"] for t in reference.get("takes", [])],
        "use_rules": "tools/twin-export/RELEASE.md: always presented as an AI; never posted publicly or uploaded",
    }


def prepare(export_dir, work_dir):
    path = os.path.join(export_dir, "journal", "records.jsonl")
    if not os.path.exists(path):
        raise VoiceError("no journal/records.jsonl: point this at a decrypted export")
    with open(path, encoding="utf-8") as f:
        records = [json.loads(line) for line in f if line.strip()]
    takes = pick_references(records, os.path.join(export_dir, "audio"))
    if not takes:
        raise VoiceError("no usable reference takes (need recordings of 6–30 s with a transcript)")
    os.makedirs(work_dir, exist_ok=True)
    ref = {"takes": takes, "total_ms": sum(t["ms"] for t in takes)}
    try:  # optional: one reference WAV for the engines
        import numpy as np
        import soundfile as sf
        parts, rate = [], None
        for t in takes:
            data, sr = sf.read(t["file"], dtype="float32", always_2d=False)
            rate = rate or sr
            if sr == rate:
                parts.append(data)
        sf.write(os.path.join(work_dir, "reference.wav"), np.concatenate(parts), rate)
        ref["wav"] = os.path.join(work_dir, "reference.wav")
    except ImportError:
        ref["wav"] = None
    with open(os.path.join(work_dir, "reference.json"), "w", encoding="utf-8") as f:
        json.dump(ref, f, indent=2)
    return ref


def speak(work_dir, text, out_wav, engine="chatterbox"):  # pragma: no cover - needs the engines
    check_engine(engine)
    with open(os.path.join(work_dir, "reference.json"), encoding="utf-8") as f:
        ref = json.load(f)
    if not ref.get("wav"):
        raise VoiceError("reference.wav missing: pip install soundfile numpy, then run prepare again")
    if engine == "chatterbox":
        import torchaudio
        from chatterbox.tts import ChatterboxTTS
        device = "cuda" if __import__("torch").cuda.is_available() else "cpu"
        model = ChatterboxTTS.from_pretrained(device=device)
        wav = model.generate(text, audio_prompt_path=ref["wav"])
        torchaudio.save(out_wav, wav, model.sr)
    else:
        from melo.api import TTS
        from openvoice import se_extractor
        from openvoice.api import ToneColorConverter
        ckpt = os.environ.get("OPENVOICE_CHECKPOINTS", "checkpoints_v2")
        conv = ToneColorConverter(os.path.join(ckpt, "converter", "config.json"), device="cpu")
        conv.load_ckpt(os.path.join(ckpt, "converter", "checkpoint.pth"))
        target, _ = se_extractor.get_se(ref["wav"], conv, vad=True)
        base = os.path.join(work_dir, "base.wav")
        tts = TTS(language="EN", device="cpu")
        key, speaker = next(iter(tts.hps.data.spk2id.items()))
        tts.tts_to_file(text, speaker, base)
        ses = os.path.join(ckpt, "base_speakers", "ses", key.lower().replace("_", "-") + ".pth")
        source = __import__("torch").load(ses, map_location="cpu")
        conv.convert(audio_src_path=base, src_se=source, tgt_se=target, output_path=out_wav, message="@MyShell")
    with open(out_wav + ".json", "w", encoding="utf-8") as f:
        json.dump(sidecar(engine, text, ref), f, indent=2)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("export_dir")
    p.add_argument("work_dir")
    s = sub.add_parser("speak")
    s.add_argument("work_dir")
    s.add_argument("text")
    s.add_argument("out_wav")
    s.add_argument("--engine", default="chatterbox")
    args = ap.parse_args(argv)
    try:
        if args.cmd == "prepare":
            ref = prepare(args.export_dir, args.work_dir)
            print("Picked %d reference takes (%.0f s)." % (len(ref["takes"]), ref["total_ms"] / 1000))
        else:
            speak(args.work_dir, args.text, args.out_wav, args.engine)
            print("Wrote %s (synthetic; see %s.json)" % (args.out_wav, args.out_wav))
    except (VoiceError, OSError, ValueError) as e:
        print("FAILED: %s" % e, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
