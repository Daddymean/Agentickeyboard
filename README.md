# Agentic Keyboard (Lumina AI Keyboard)

An Android custom keyboard (IME) built with Jetpack Compose that pairs a
standard QWERTY layout with Gemini-powered writing assistance and a fully
on-device personalization engine.

## Features

### AI writing tools (Gemini)
- **Fix Grammar** — corrects spelling/grammar and explains what changed; one tap to apply.
- **Proofread as you type** (opt-in) — a debounced background grammar check surfaces a one-tap fix chip while you write.
- **Compose** — type an instruction ("tell her I'll be 20 min late, apologetic") and get the actual message drafted in your voice.
- **Rewrite** — restyles your draft to match your selected persona; long-press the chip to cycle personas from the keyboard.
- **Continue** — extends your draft mid-thought in your own style.
- **Summarize** — condenses long text into 1–2 sentences.
- **Translate** — translates between configurable source/target languages.
- **Analyze Tone** — detects sentiment/tone with actionable tips plus tone-matched emoji you can insert with a tap.
- **Smart Replies** — three context-aware responses at three lengths (short / medium / detailed).
- **Clipboard intelligence** — tap 📋 to paste, translate, summarize, explain in plain language, or generate replies to whatever you just copied (e.g. the message you're answering).

Every request is enriched with a locally computed personalization context
(your dominant tone and most-used vocabulary), repeated identical requests are
served from an in-memory cache, and starting a new action cancels the one in
flight.

### On-device intelligence (works offline)
- **Swipe-to-type** backed by a 10,000-word frequency-ranked dictionary plus your personal vocabulary, with a live gesture trail and word preview.
- **Smart space bar** — committing a word with space expands shortcut templates (`omw` → "On my way! Running late."), applies learned typo corrections, and feeds vocabulary learning. Double-tap space inserts a period.
- **Auto-correction undo** — backspace right after a correction restores what you typed; reverting the same rule twice deletes it.
- **Learned auto-corrections** — typo→fix rules are extracted automatically whenever the AI corrects your grammar, then applied locally as you type.
- **Next-word prediction** — an on-device bigram model learns your word pairs and predicts the next word after each space.
- **Prefix predictions** built from your own most-frequent words.
- **Per-app personas** — the keyboard remembers which persona you use in each app (Professional in email, Casual in chat).
- **Shortcut templates** — user-defined abbreviations that expand to full phrases.
- **Command gestures** — diagonal swipes over the keyboard trigger Translate / Summarize / Tone / Expand; horizontal swipes are Space/Backspace.
- **Typing mechanics** — auto-capitalization at sentence starts, one-shot shift with double-tap caps lock, long-press accents (é, ñ, ü…) and punctuation variants, hold-to-repeat backspace, slide-on-space cursor control, optional number row, and a mic key that hands off to your voice IME.

### Privacy
- **Password fields are detected automatically** — AI features, logging, and learning all shut off in secure fields.
- **Offline mode toggle** blocks all cloud calls; local fallbacks keep the tools functional. The toggle (and all settings) persist across restarts.
- **Always-on cloud request redaction** — the final serialized Gemini request is sanitized immediately before transmission, replacing credential-shaped secrets, emails, phone numbers, financial identifiers, SSNs, IP addresses, URLs, and long numeric IDs with neutral markers.
- **Pause learning** — an incognito switch for the personalization engine.
- **Data retention** — writing logs auto-expire after 7/30/90 days (your choice).
- **Cloud backup disabled** — your typing history never leaves the device via Android backup.
- **No request logging in release builds**; the API key is sent as a header and redacted from debug logs.
- **Style Hub export & import** — serialize your personalization model to JSON (or Base64) with optional redaction of emails, phone numbers, financial numbers, IPs, URLs, and numeric IDs, then restore it on another device.
- **Usage dashboard** — on-device stats for auto-fixes, swipes, AI applies, and shortcut expansions.

## Project layout

```
app/src/main/java/io/github/daddymean/agentickeyboard/
├── AgenticKeyboardApplication.kt   # App singleton exposing the Room repository
├── MainActivity.kt                 # Companion app: playground, shortcuts, Style Hub, setup guide
├── service/AgenticKeyboardService.kt  # The InputMethodService hosting the Compose keyboard
├── ui/
│   ├── AgenticKeyboardLayout.kt    # Keyboard UI: keys, AI shelf, gestures, swipe-to-type
│   └── KeyboardViewModel.kt        # State + AI orchestration + on-device learning
├── network/
│   ├── RetrofitClient.kt           # Gemini API client
│   └── CloudRedactionInterceptor.kt # Last-mile outbound privacy guard
├── db/DatabaseModels.kt            # Room entities, DAOs, repository
└── util/
    ├── CloudTextSanitizer.kt        # Pure-JVM sensitive-value redaction
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

Covered areas: cloud request redaction, privacy-aware export, JSON integrity,
swipe-to-type decoding, and keyboard layout screenshots.
