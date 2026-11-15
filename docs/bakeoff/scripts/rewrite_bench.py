"""Rewrite bench on box CPU (x86_64, 4 threads), NOT a phone. Synthetic prompts only.
Fresh Engine per prompt (LFM3 card: one Engine per conversation on LiteRT-LM <= 0.19.1).
Sampler = the app's LiteRtGemmaEngine sampler: top_k 10, top_p 0.9, temperature 0.2, seed 0."""
import json, sys, time, resource, litert_lm
from prompts import CASES, prompt
model, out, thinking_off = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
limit = int(sys.argv[4]) if len(sys.argv) > 4 else len(CASES)
SAMPLER = litert_lm.SamplerConfig(top_k=10, top_p=0.9, temperature=0.2, seed=0)
rows = []
for cid, tone, text, facts in CASES[:limit]:
    t0 = time.perf_counter()
    eng = litert_lm.Engine(model, backend=litert_lm.Backend.CPU(thread_count=4),
                           cache_dir=__import__("os").environ.get("BAKEOFF_CACHE", "/tmp/litert-cache"), enable_benchmark=True)
    load_s = time.perf_counter() - t0
    kw = dict(sampler_config=SAMPLER)
    if thinking_off: kw["thinking_config"] = litert_lm.ThinkingConfig(enable_thinking=False)
    conv = eng.create_conversation(**kw)
    s = time.perf_counter(); first = None; parts = []
    for msg in conv.send_message_async(prompt(tone, text), max_output_tokens=256):
        for c in (msg.get("content") or []) if isinstance(msg, dict) else []:
            if c.get("type") == "text" and c.get("text"):
                if first is None: first = time.perf_counter()
                parts.append(c["text"])
    end = time.perf_counter()
    reply = "".join(parts)
    try:
        bi = conv.get_benchmark_info(); bi = bi.__dict__
    except Exception as e:
        bi = {"error": str(e)}
    try: ntok = len(eng.tokenize(reply))
    except Exception: ntok = None
    rows.append({"id": cid, "tone": tone, "input": text, "facts": facts, "output": reply,
                 "load_s": load_s, "ttft_s": (first - s) if first else None, "total_s": end - s,
                 "out_tokens": ntok, "bench": bi})
    print(json.dumps({k: rows[-1][k] for k in ("id", "ttft_s", "total_s", "out_tokens")}), bi.get("last_decode_tokens_per_second"), flush=True)
    conv.close(); eng.close()
res = {"model": model, "thinking_off": thinking_off, "rows": rows,
       "peak_rss_mib": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024}
json.dump(res, open(out, "w"), indent=1)
print("peak_rss_mib", res["peak_rss_mib"])
