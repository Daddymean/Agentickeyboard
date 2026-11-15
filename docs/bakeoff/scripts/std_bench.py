"""LiteRT-LM built-in Benchmark, box CPU (x86_64, 4 threads), NOT a phone."""
import sys, json, resource, litert_lm, dataclasses
m, out = sys.argv[1], sys.argv[2]
b = litert_lm.Benchmark(m, litert_lm.Backend.CPU(thread_count=4), prefill_tokens=256, decode_tokens=128, cache_dir=__import__("os").environ.get("BAKEOFF_CACHE", "/tmp/litert-cache"))
r = b.run()
d = dataclasses.asdict(r) if dataclasses.is_dataclass(r) else r.__dict__
d["peak_rss_mib"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1024
json.dump(d, open(out, "w"), indent=1); print(json.dumps(d))
