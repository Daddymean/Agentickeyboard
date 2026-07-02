# Agentic Keyboard (Lumina AI Keyboard)

An Android custom keyboard (IME) built with Jetpack Compose that pairs a
standard QWERTY layout with Gemini-powered writing assistance and a fully
on-device personalization engine.

## Features

### AI writing tools (Gemini)
- **Fix Grammar** — corrects spelling/grammar and explains what changed; one tap to apply.
- **Rewrite** — restyles your draft to match your selected persona (Professional, Joyful, Empathetic, Casual, or "Match my history").
- **Summarize** — condenses long text into 1–2 sentences.
- **Translate** — translates between configurable source/target languages.
- **Analyze Tone** — detects sentiment/tone with actionable tips.
- **Smart Replies** — suggests three short, context-aware responses.

Every request is enriched with a locally computed personalization context
(your dominant tone and most-used vocabulary) so results match how you write.

### On-device intelligence (works offline)
- **Swipe-to-type** with a live gesture trail and word preview, biased toward your personal vocabulary.
- **Smart space bar** — committing a word with space expands shortcut templates (`omw` → "On my way! Running late."), applies learned typo corrections, and feeds vocabulary learning. Double-tap space inserts a period.
- **Learned auto-corrections** — typo→fix rules are extracted automatically whenever the AI corrects your grammar, then applied locally as you type.
- **Predictive suggestions** built from your own most-frequent words.
- **Shortcut templates** — user-defined abbreviations that expand to full phrases.
- **Command gestures** — diagonal swipes over the keyboard trigger Translate / Summarize / Tone / Expand; horizontal swipes are Space/Backspace.
- **Shift** — tap for one-shot shift, double-tap for caps lock; long-press backspace repeats.

### Privacy
- **Offline mode toggle** blocks all cloud calls; local fallbacks keep the tools functional.
- **Cloud backup disabled** — your typing history never leaves the device via Android backup.
- **No request logging in release builds**; the API key is sent as a header and redacted from debug logs.
- **Style Hub export** serializes your personalization model to JSON (or Base64) with optional redaction of emails, phone numbers, financial numbers, IPs, URLs, and numeric IDs.

## Project layout

```
app/src/main/java/com/example/
├── AgenticKeyboardApplication.kt   # App singleton exposing the Room repository
├── MainActivity.kt                 # Companion app: playground, shortcuts, Style Hub, setup guide
├── service/AgenticKeyboardService.kt  # The InputMethodService hosting the Compose keyboard
├── ui/
│   ├── AgenticKeyboardLayout.kt    # Keyboard UI: keys, AI shelf, gestures, swipe-to-type
│   └── KeyboardViewModel.kt        # State + AI orchestration + on-device learning
├── network/                        # Retrofit client + Gemini request/response models
├── db/DatabaseModels.kt            # Room entities, DAOs, repository
└── util/
    ├── SwipeToTypeEngine.kt        # Path-matching swipe decoder
    └── PersonalModelSerializer.kt  # Privacy-aware personalization export
```

## Run locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open the project directory in Android Studio.
2. Create a `.env` file in the project root and set `GEMINI_API_KEY` to your
   [Gemini API key](https://aistudio.google.com/apikey) (see `.env.example`).
   Without a key the app still runs using its offline fallbacks.
3. Run the `app` configuration on an emulator or device.

### Enable the keyboard
1. In the app, open the **Setup Guide** tab and tap *Open System Keyboards*
   (or go to Settings → System → Languages & input → On-screen keyboard).
2. Enable **Agentic Keyboard**, then switch to it via the input-method picker.

## Testing

Unit tests (Robolectric, Roborazzi screenshots, and plain JUnit) live in
`app/src/test`:

```bash
./gradlew testDebugUnitTest
```

Covered areas: privacy redaction/sanitization, JSON export integrity,
swipe-to-type decoding, and a keyboard layout screenshot test.
