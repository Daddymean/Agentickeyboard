# Handwriting capture (KEYBOARD-037)

Keith can answer any journal question by hand (Journal → Answer → "Write it by hand instead"). Each answer keeps:

- **The strokes, exactly as written:** x and y in view pixels, and time in ms from the first point, stored as `body.ink`.
- **A PNG of the page:** `body.ink_png`, base64.
- **What it says:** `body.text`, which Keith types or corrects. `body.ink_text_source` records how the text was produced.

All of this is one ordinary journal answer, encrypted at rest with the twin key, and removed by delete-all. Only `text` can become a memory candidate (the allowed fields are unchanged); strokes and the image are never learned. The export keeps the image byte for byte, and `decrypt_twin_export.py` writes it to `journal/handwriting/<record id>.png`.

## Recognition: ML Kit Digital Ink, and why it is gated
Checked on 2026-10-19 against Google's docs (developers.google.com/ml-kit/vision/digital-ink-recognition/android and /ml-kit/android-data-disclosure):

- **Recognition runs on the device**, but each language needs a model of about 20 MB. `RemoteModelManager.download()` fetches it from Google. It is **not bundled**, so it is a network fetch: one time per language, kept until deleted.
- **ML Kit SDKs also send Google diagnostics and usage data.** That covers the device model and OS, the app's package and version, per-installation identifiers, performance metrics, event types (initialisation, model download, recognition), input and output sizes, and for Digital Ink the **configured language**. Google says this is HTTPS and not shared with third parties. The **ink and the recognised text are not listed** as collected. This is the same SDK family the keyboard already uses for GenAI.

**So:**
- Recognition stays **off until Keith approves** a disclosure that says all of the above. The approval is a journal event (`type: ink_model`), so it is on record and in the export.
- After approval, the model downloads once, **on Wi-Fi only**.
- Without approval, handwriting is still saved and Keith types what it says.
- Settings → "remove the model and turn it off" deletes the model and records the revocation.
- Only `InkRecognizer.kt` imports ML Kit Digital Ink, and every call checks the approval (`HandwritingTest`).

The ML Kit library's manifest merges in `INTERNET`, which the app already has, and `ACCESS_NETWORK_STATE`. Like the GenAI libraries the keyboard already uses, it also registers ML Kit's component-discovery service.

**Decision for Keith:** accept ML Kit's diagnostics for handwriting, or keep recognition off and type the text.
