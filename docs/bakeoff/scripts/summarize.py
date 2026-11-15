import json, statistics as st, re, sys
def strip(t):
    t = re.sub(r"(?s)<think>.*?</think>", "", t); i = t.rfind("</think>")
    return (t[i+8:] if i >= 0 else t).strip()
def load(p):
    d = json.load(open(p)); R = d["rows"]
    med = lambda xs: st.median([x for x in xs if x is not None])
    s = dict(peak=d["peak_rss_mib"],
        ttft=med([r["bench"]["time_to_first_token_in_second"] for r in R])*1000,
        ttft_wall=med([r["ttft_s"] for r in R])*1000,
        prefill=med([r["bench"]["last_prefill_tokens_per_second"] for r in R]),
        decode=med([r["bench"]["last_decode_tokens_per_second"] for r in R]),
        init=med([r["load_s"] for r in R]), first_init=R[0]["load_s"],
        dtok=med([r["bench"]["last_decode_token_count"] for r in R]),
        total=med([r["total_s"] for r in R])*1000)
    leaks = [r["id"] for r in R if "think>" in r["output"]]
    facts = sum(all(f.lower() in strip(r["output"]).lower() for f in r["facts"]) for r in R)
    quotes = [r["id"] for r in R if strip(r["output"]).startswith(('"', "Here", "Sure"))]
    s.update(leaks=leaks, facts=facts, n=len(R), preamble=quotes)
    # length ratio
    s["shorten_ratio"] = st.median([len(strip(r["output"]))/len(r["input"]) for r in R if r["tone"]=="SHORTEN"])
    s["elab_ratio"] = st.median([len(strip(r["output"]))/len(r["input"]) for r in R if r["tone"]=="ELABORATE"])
    return s, R
for p in sys.argv[1:]:
    s, _ = load(p); print(p, json.dumps(s, default=str))
