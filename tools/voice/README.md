# Keith's voice for the twin (KEYBOARD-038)

## Phase 1: the raw recordings (done, and they come first)
Every journal answer, capsule and voice-script take is kept as **lossless FLAC** (48 kHz mono). It is encrypted on the phone and exported bit for bit (`audio/<id>.flac`). The kids hear Keith's real voice first. Nothing here replaces or alters those files.

## Phase 2: synthesis, on Keith's computer (this folder)
`twin_voice.py` runs on Keith's own computer, against an export he has already decrypted:

```
python3 twin_voice.py prepare twin-data/ voice-work/      # picks 6–30 s takes with transcripts, up to 3 min
python3 twin_voice.py speak voice-work/ "Proud of you." out.wav            # Chatterbox (default)
python3 twin_voice.py speak voice-work/ "Proud of you." out.wav --engine openvoice
```

- **Engines:** Chatterbox (Resemble AI, **MIT**, `pip install chatterbox-tts`) is the default. Its output carries Resemble's Perth watermark, which marks the audio as synthetic. OpenVoice V2 (MyShell, **MIT** since April 2024) is the alternative: MeloTTS for the base speech, then the tone-colour converter.
- **Refused by the script:** F5-TTS (CC-BY-NC weights), XTTS v2 / Coqui (CPML, non-commercial), and ZipVoice's Emilia-trained weights (CC-BY-NC data).
- **Labelling:** every output gets `out.wav.json`, which says `synthetic: true`, carries the AI label "AI twin built from Dad's own words. This is not Dad.", and records the engine, its licence and the journal records the reference came from.
- **Network:** the script itself has no network code. The engines download their weights from Hugging Face on first use; after that, `HF_HUB_OFFLINE=1` keeps them offline. No audio is uploaded anywhere.
- **Use rules:** those in `tools/twin-export/RELEASE.md` apply. Synthetic audio is always presented as an AI and never posted or uploaded publicly.

## On the phone: researched, not shipped yet
Checked 2026-10-19:

- **Chatterbox:** there is no official ONNX or mobile build. The community `chatterbox-nano-ONNX` is four graphs, non-streaming and unvalidated on Android, and Resemble has said it has no plans for official ONNX. Porting it means the tokenizer, KV cache and sampling in Kotlin or C++, plus keeping the Perth watermark (applied in Python today). **Computer-side only for now.**
- **OpenVoice V2 via sherpa-onnx:** sherpa-onnx (Apache-2.0) runs **MeloTTS** (MIT) on Android, but it does **not** include OpenVoice's tone-colour converter. Its built-in cloning models are ZipVoice and Pocket. ZipVoice's public weights are trained on Emilia (CC-BY-NC), so they're out. A community C++/OpenCL port of the OpenVoice V2 converter (a8nova/adreno-llms, June 2026) runs at about 2× slower than real time on an Adreno 620. Its licence and app-linkability still need checking.
- **A realistic on-device plan** has three steps:
  1. sherpa-onnx MeloTTS for the base speech.
  2. The OpenVoice V2 tone-colour converter as ONNX (`tone_color.onnx`), on onnxruntime-android.
  3. Keith's 256-d tone embedding, extracted on the computer and imported in an encrypted export.

  The cost is roughly 200–300 MB of models in the APK or side-loaded (the twin has no network), native libraries, and about 2× real time on mid-range phones.
- **Recommendation:** keep synthesis on the computer for now. Revisit on-device after a device benchmark, which is Keith's call because of the APK size.
