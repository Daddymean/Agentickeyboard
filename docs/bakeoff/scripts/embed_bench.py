"""Embedding bench on box CPU (x86_64), NOT a phone. Synthetic data only."""
import json, sys, time, resource, numpy as np, litert_lm
model, out = sys.argv[1], sys.argv[2]
threads = 4
notes = json.load(open("../data/notes.json"))["notes"]; queries = json.load(open("../data/queries.json"))["queries"]
DOC = "task: search result | text: "; QRY = "task: search query | text: "
t0 = time.perf_counter()
eng = litert_lm.EmbeddingEngine(model, backend=litert_lm.Backend.CPU(thread_count=threads), cache_dir=__import__("os").environ.get("BAKEOFF_CACHE", "/tmp/litert-cache"))
load_s = time.perf_counter() - t0
opts = litert_lm.EmbeddingOptions(normalize=True)
def emb(t):
    s = time.perf_counter()
    r = eng.compute_embedding(t, opts)
    v = np.array(r.embedding if hasattr(r,'embedding') else r, dtype=np.float32)
    return v, time.perf_counter() - s
# warm-up (not timed)
emb(DOC + "warm up")
D, dl = zip(*[emb(DOC + n["text"]) for n in notes]); Q, ql = zip(*[emb(QRY + q["query"]) for q in queries])
D = np.stack(D); Q = np.stack(Q)
S = Q @ D.T
ids = [n["id"] for n in notes]
res = {"model": model, "threads": threads, "dim": int(D.shape[1]), "load_s": load_s,
       "doc_ms_mean": 1000*float(np.mean(dl)), "query_ms_mean": 1000*float(np.mean(ql)),
       "doc_ms_p50": 1000*float(np.median(dl)), "query_ms_p50": 1000*float(np.median(ql))}
r = {1: [], 3: [], 5: []}; mrr = []; neg = []
for qi, q in enumerate(queries):
    order = [ids[j] for j in np.argsort(-S[qi])]
    gold = set(q.get("gold") or [])
    if not gold:
        neg.append({"id": q["id"], "top1": order[0], "score": float(S[qi].max())}); continue
    for k in r: r[k].append(len(gold & set(order[:k])) / len(gold))
    mrr.append(next(1.0/(i+1) for i, x in enumerate(order) if x in gold))
res.update({f"recall@{k}": float(np.mean(v)) for k, v in r.items()})
res["mrr"] = float(np.mean(mrr)); res["n_gold_queries"] = len(mrr); res["negatives"] = neg
eng.close() if hasattr(eng, "close") else None
res["peak_rss_mib"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
np.save(out + ".docs.npy", D); np.save(out + ".queries.npy", Q)
json.dump(res, open(out + ".json", "w"), indent=1); print(json.dumps({k: v for k, v in res.items() if k != "negatives"}))
