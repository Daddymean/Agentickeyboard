# On-device model bake-off (Nov 14, 2026)

LFM3-1.7B against the current Gemma 4 E2B rewrite model, EmbeddingGemma 2 740M against the current 270M twin-memory embedder, and a feasibility check of EmbeddingGemma 2 audio transcription. Run by Hax on Saturday, November 14, 2026, 8:25 to 9:10 PM PT.

**Box numbers are box CPU (x86_64, 4 threads), not phone.** The box has no phone or GPU. Phone cells stay "not measured" until Keith runs the debug harness ([How to run the phone benchmark](#how-to-run-the-phone-benchmark)). Every prompt and memory is synthetic.

## What the app uses today (main @1aee381)

| Role | Model file (pinned in `ModelCatalog`) | Size | License |
|---|---|---:|---|
| Rewrite (on-device, ML Kit fallback, 20 s timeout) | `litert-community/gemma-4-E2B-it-litert-lm@3d48132` `gemma-4-E2B-it.litertlm` | 2,588,147,712 B | Apache-2.0 |
| Twin memory embedder (#209) | `litert-community/embeddinggemma-2-text-270m-litert-lm@9be6e8b` `embeddinggemma-2-text-270m.litertlm` | 164,626,432 B | Apache-2.0 |

The twin already runs EmbeddingGemma 2, as the **text-only 270M** package. So part 2 compares the same model family in two packages: the text-only 270M against the full 740M with vision and audio. Both have the same 270M text backbone. It is not a comparison of different embedding models.

The SHA-256 of every file used here matched its pin or its model card: Gemma `181938…a63c` and EG2 270M `2d079e…86fb` match `ModelCatalog`, and LFM3 int4 `0400d8…1011196` matches the LFM3 card. The SHA-256 of the 740M file was `e7a8a2…77f4c`.

## 1. Rewrite: LFM3-1.7B vs Gemma 4 E2B

Setup: LiteRT-LM 0.19.1 (Python, the same version as the app's `litertlm-android` 0.19.1), CPU backend with 4 threads. Each run used the app's sampler (top-k 10, top-p 0.9, temperature 0.2, seed 0) and the app's exact rewrite prompt (`GemmaLitertEngine.promptForRewrite`). Each of the 20 prompts got a fresh Engine; the LFM3 card says to use one Engine per conversation on LiteRT-LM 0.19.1 or earlier. LFM3 ran with thinking off. Gemma ran as the app runs it.

| | Gemma 4 E2B (current) | LFM3-1.7B mixed int4, 2k KV | Source |
|---|---:|---:|---|
| Rewrite quality, 20 synthetic prompts (pass / minor / fail) | **18 / 2 / 0** | 11 / 1 / 8 | box, one rater |
| Raw output leaked `…</think>` with thinking off | 0 / 20 | 4 / 20 | box |
| Time to first token, median of the 20 rewrites | **259 ms** | 1,133 ms | box CPU (x86_64), not phone |
| Prefill tok/s, median of the 20 rewrites (prompts of 60 to 90 tokens) | **225** | 61 | box CPU (x86_64), not phone |
| Decode tok/s, median of the 20 rewrites | 28.0 | 29.2 | box CPU (x86_64), not phone |
| Standard bench, 256 prefill / 128 decode (2 runs): TTFT | **461 / 489 ms** | 801 / 938 ms | box CPU (x86_64), not phone |
| Standard bench: prefill tok/s | **602 / 567** | 333 / 285 | box CPU (x86_64), not phone |
| Standard bench: decode tok/s | 27.8 / 27.0 | 30.7 / 26.1 | box CPU (x86_64), not phone |
| Peak RSS (VmHWM), standard bench | **1,273 MiB** | 1,904 to 1,913 MiB | box CPU (x86_64), not phone |
| Peak RSS (VmHWM), 20 rewrites in one process | **1,448 MiB** | 2,224 MiB | box CPU (x86_64), not phone |
| Engine init, warm cache (median) | **0.17 s** | 2.13 s | box CPU (x86_64), not phone |
| TTFT / decode / peak RAM on Keith's phone | not measured | not measured | phone harness in this PR |
| Download (not in the APK) | 2.59 GB | **1.44 GB** (int8: 1.80 GB, not run) | HF file sizes |
| APK impact | none | none (no model is bundled; the harness is debug-only) | build |
| License | Apache-2.0 | LFM Open License v1.0 (not OSI-approved) | model cards |

Phone numbers that Liquid/litert-community published on the LFM3 card (**vendor-reported, not ours**), mixed int4 with 2k KV:

| Phone | Backend | TTFT | Decode tok/s | Peak memory |
|---|---|---:|---:|---:|
| Galaxy S26 | GPU | 0.81 s | 32.4 | about 1,002 to 1,012 MB |
| Galaxy S26 | CPU, 4 threads | 3.33 s | 35.5 | about 2,211 MB |
| Pixel 8a | CPU, 4 threads | 9.23 s | 14.7 | about 1,966 MB |

The vendor TTFT is for a 1,024-token prefill, which is much longer than a keyboard rewrite prompt.

### Quality: what went wrong

The side-by-side outputs are in [`rewrite-outputs.md`](rewrite-outputs.md). One rater (Hax), not blind, one sample per prompt.

- LFM3 failed 8 of 20:
  - It refused one input (`e1`: "I'm sorry, but I can't help with that").
  - It reversed or garbled the meaning of 4 (`f4` swapped who returns the drill, `p5` swapped who sends the invite, `e4` turned a past move into an "upcoming" one, `s5` became "have I heard back?").
  - It invented facts on 3 (`e3`, `e5`, `f5`).
  - Its "elaborate" rewrites ran about 15 times the input length, against about 6 times for Gemma.
- Even with `enable_thinking=false`, 4 of LFM3's 20 raw replies began with a draft followed by `</think>` and then a repeat of the answer. The LFM3 card documents this for the mixed-int4 files. A shipping integration would have to strip it.
- Gemma had no failures. Two outputs were weak: in `f5` the "friendly" rewrite is barely friendlier, and in `e3` it added "dealership or garage". Its "elaborate" outputs read stiff and formal, but they stayed faithful.

### What an LFM3 swap would need in the app (not done here)

1. Pass `ThinkingConfig(false)` and strip a stray `…</think>`.
2. Create a new Engine for every rewrite. `LiteRtGemmaEngine` keeps one Engine and opens several conversations on it, which carries short-convolution state over on LiteRT-LM 0.19.1 (LiteRT-LM #3165). The fix is on LiteRT-LM main and is not in a release yet. On the box, a fresh LFM3 Engine took about 2.1 s to initialize.
3. Get Keith's licence sign-off (see License).

## 2. Embeddings: EmbeddingGemma 2 270M text (current) vs 740M

Setup: a synthetic retrieval set of 50 fake notes and 42 queries. 39 queries have gold notes; 3 are negatives with no gold. It is the same synthetic set as the Nov 9 shootout and is in [`data/`](data/). The run used the official prefixes the app uses (`task: search result | text: ` / `task: search query | text: `), normalized 768-d vectors, LiteRT-LM 0.19.1 `EmbeddingEngine`, CPU with 4 threads, and 3 runs per model.

| | 270M text (current) | 740M (text + vision + audio) | Source |
|---|---:|---:|---|
| recall@1 / @3 / @5 (macro over 39 gold queries) | 0.936 / 1.000 / 1.000 | 0.936 / 1.000 / 1.000 | box |
| MRR | 1.000 | 1.000 | box |
| Cosine between the two models' vectors, same text | docs: mean 0.99979, min 0.9953; queries: mean 0.99999, min 0.99958 | ← | box |
| Embed latency, mean per text (3 runs) | 22.7 to 31.8 ms | 22.4 to 31.1 ms | box CPU (x86_64), not phone |
| Engine load, warm cache | 0.35 to 0.78 s | 0.60 to 1.76 s | box CPU (x86_64), not phone |
| Peak RSS (VmHWM), text only | **407 to 411 MiB** | 451 to 501 MiB | box CPU (x86_64), not phone |
| Phone embed latency / RAM | not measured | not measured | phone harness in this PR |
| Download | **164.6 MB** | 484.6 MB (+320 MB) | HF file sizes |

recall@1 is below 1 only because some queries have two gold notes. MRR is 1.0 because the top hit was always gold. Latency differences are within run-to-run noise. On text, the two packages give the same vectors to within rounding, so the 740M package only adds image and audio inputs. It does not improve text retrieval.

## 3. Transcription feasibility (EmbeddingGemma 2 audio + eg2-transcribe)

**Verdict: feasible on a phone, but not by swapping in the 740M `.litertlm`.** That file maps audio to a 768-d *embedding* for search; it does not produce text. [eg2-transcribe](https://github.com/john-rocky/eg2-transcribe) (Apache-2.0, commit `d676d99`) adds three things:

- its own export of the EG2 audio encoder;
- a separately trained CTC "reader" that picks one of 200,000 English words, or a blank, for every 40 ms of audio;
- a log-mel front end.

Those run on plain LiteRT (`com.google.ai.edge.litert`), not LiteRT-LM.

Quick box test (local only, nothing added to the app):

- Inputs:
  - the 10 s window files from `mlboydaisuke/EmbeddingGemma-2-Transcribe-LiteRT@b0fccaa`, SHA-256 checked;
  - Python `ai-edge-litert` with 4 threads;
  - a front end I wrote in numpy from the repo's documented spec. It was not bit-checked against theirs.
- Audio: 63 LibriSpeech dev-clean clips of 1.6 to 9.9 s (333.7 s in all, CC BY 4.0, from `hf-internal-testing/librispeech_asr_dummy`). They are out of the reader's training set but are the same read-audiobook domain it was trained on, so expect worse results on real speech.
- **WER 8.6 %** (26 of 63 clips were perfect).
- **4.2× real time**, features included. Box CPU (x86_64), not phone.
- **Peak RSS 3,552 MiB**: the fp16 weights expand in the interpreter. Box CPU (x86_64), not phone.
- The project reports its own phone runs on a 54-minute recording (vendor-reported, not ours): Galaxy S26 GPU 23.1× real time and Pixel 8a GPU 10.8×, both at 3.81 % WER.

What it would take:

| Item | Cost |
|---|---|
| Model download for 10 s windows | 589 MB encoder + 387 MB reader + 1.7 MB vocab, about **0.98 GB**. With 30 s windows too, about 1.95 GB. |
| Output | English only, lower case, no punctuation. Trained only on read audiobooks (LibriSpeech train-clean-100). |
| Permission | `RECORD_AUDIO` for live dictation, which makes it a **HOLD for Keith** microphone PR. Transcribing an existing file needs no permission. |
| Licences | Code and models are Apache-2.0. The reader is trained on LibriSpeech (CC BY 4.0, attribution). The NOTICE says EG2 deployments must follow the Gemma Prohibited Use Policy. |

No microphone or permission code is in this PR.

## Recommendation

1. **Keep Gemma 4 E2B for rewrite.** Do not swap to LFM3.
   - Against LFM3: on the same 20 synthetic prompts it changed the meaning or invented facts 8 times, against 0 for Gemma. It leaks `</think>`. On box CPU its TTFT was about 4× slower and its peak RSS about 1.5× higher. Its licence is not OSI-approved, which conflicts with the open-source-only rule.
   - For LFM3: it is a 1.15 GB smaller download, with similar decode speed.
   - Revisit only if Keith's phone numbers show Gemma 4 E2B is too slow or too large on his phone. Even then, start with LFM3 int8, which the card says stays closer to the source model.
2. **Keep EmbeddingGemma 2 270M text for twin memory.** The 740M package returns the same text vectors (cosine ≥ 0.995) for +320 MB of download and +40 to 90 MiB of RAM. Switch only if we decide to search photos or voice memos, which would be a separate, HOLD-for-Keith feature.
3. **Transcription: park it as a separate proposal.** It works on the box (8.6 % WER on clean read speech), but it is a second ~1 GB pipeline on a different runtime and needs the microphone permission. If Keith wants it, scope a HOLD PR for transcribing existing audio files first, with no mic.

No model swap is wired in this PR, so there is nothing to flag on. The only new code is the debug-only benchmark harness, and it is off by default.

## License (checked on the model cards, Nov 14, 2026)

- **LFM3-1.7B** (`litert-community/LFM3-1.7B-LiteRT@5cabb9d`, base `LiquidAI/LFM3-1.7B`): **LFM Open License v1.0** from Liquid AI, Inc. The card says `license: other`, `license_name: lfm1.0`, and the repo includes the LICENSE file unchanged. Key terms, quoted:
  - §2–3 grant a "perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable" copyright and patent license, "Subject to … the Commercial Use limitation set forth in Section 5".
  - §4 sets Apache-style redistribution conditions: give recipients a copy of the License, mark modified files, keep notices and the NOTICE text.
  - §5: "The rights granted under this License for Commercial Use are conditioned upon You or Your Legal Entity not exceeding the Threshold." The Threshold is "annual revenue of 10 million United States dollars ($10,000,000) or more". "Any Commercial Use … by a Legal Entity that exceeds the Threshold is not licensed under this Agreement." Qualified non-profits are exempt for non-commercial or research use.
  - §11: the license "will terminate automatically and immediately if You fail to comply".
  - It is **not OSI-approved**: the revenue-based restriction does not meet the Open Source Definition.
  - The relay said "free below $10M revenue". More precisely, use is licensed for entities with annual revenue below $10M; at $10M or more, commercial use is not licensed.
- **EmbeddingGemma 2** (both `litert-community/embeddinggemma-2-740m-litert-lm@66011bd` and the shipped 270M package; base `google/embeddinggemma-2@f9972ac`): **Apache-2.0** on the cards, ungated. It is not under the Gemma Terms of Use. eg2-transcribe's NOTICE adds that deployments must follow the Gemma Prohibited Use Policy.
- **eg2-transcribe** (`john-rocky/eg2-transcribe@d676d99`): **Apache-2.0**, per both the LICENSE file and GitHub's SPDX field. Its model repos (`mlboydaisuke/EmbeddingGemma-2-Transcribe-LiteRT`, `-Reader`) are Apache-2.0 with a NOTICE; the reader's training data is LibriSpeech, CC BY 4.0. No code from it is in this PR. The local test script follows its documented front-end spec, with credit.
- **LiteRT-LM** is already approved. Box-only tools that are not shipped: `litert-lm` 0.19.1 and `ai-edge-litert` from pip.

## How to run the phone benchmark

Everything below is synthetic. No twin data is read, and no permission is requested.

1. Build a debug APK with the harness turned on: `./gradlew :app:assembleDebug -PmodelBenchmark=true`. Without that flag, the screen is disabled in the manifest and `BuildConfig.MODEL_BENCHMARK` is `false`; release builds never contain it. Install over your build with `adb install -r` (the same `debug.keystore`, so twin data stays).
2. Copy the model files into the app's own folder; the app downloads nothing:
   ```sh
   P=com.aistudio.agentickeyboard.vqkymt
   adb shell mkdir -p /sdcard/Android/data/$P/files/bench
   adb push LFM3-1.7B_mixed_int4_ekv2048.litertlm /sdcard/Android/data/$P/files/bench/
   adb push embeddinggemma-2-740m.litertlm /sdcard/Android/data/$P/files/bench/
   # Gemma 4 E2B and EG2 270M use the copies already downloaded in Settings; push them here too if not.
   ```
3. Open **Model benchmark** from the launcher and tap a model and backend (CPU or GPU). From adb you can instead run `adb shell am start -n $P/io.github.daddymean.agentickeyboard.bench.ModelBenchmarkActivity --es model lfm3-int4 --es backend gpu`. The keys are `gemma4-e2b`, `lfm3-int4`, `lfm3-int8`, `eg2-270m` and `eg2-740m`. Run `adb shell am force-stop $P` between models so each peak-RAM figure is clean.
4. Each run writes `files/bench/result-<model>-<backend>-<time>.md`, with a table row (TTFT, prefill and decode tok/s, VmHWM) and the 20 outputs. Fetch it with `adb pull /sdcard/Android/data/$P/files/bench/` and paste the rows into the phone column.
5. Clean up with `adb shell rm -r /sdcard/Android/data/$P/files/bench`.

## Files

- `rewrite-outputs.md`: 20 side-by-side rewrites with verdicts.
- `results/`: raw JSON from every box run.
- `scripts/`: the box scripts, which need `pip install litert-lm==0.19.1` (plus `ai-edge-litert soundfile pyarrow` for transcription).
- `data/`: the synthetic retrieval set.
- `app/src/debug/.../bench/`: the debug-only phone harness. Its tests are in `app/src/testDebug/.../bench/BenchStatsTest.kt`.
