import json, re
def strip(t):
    t = re.sub(r"(?s)<think>.*?</think>", "", t); i = t.rfind("</think>")
    return (t[i+8:] if i >= 0 else t).strip()
JUDGE = {  # one rater (Hax), not blind, after reading both outputs. pass / minor / fail
 "s1":("pass","pass (raw: `</think>` leak, duplicated text)"),
 "s2":("pass","pass (raw: `</think>` leak, duplicated text)"),
 "s3":("pass","pass"),
 "s4":("pass","pass (barely shorter)"),
 "s5":("pass","fail: \"have I heard back?\" garbles the meaning"),
 "e1":("pass","fail: refused (\"I'm sorry, but I can't help with that\")"),
 "e2":("pass (stiff)","minor: invents salad suggestions and asks about dietary needs"),
 "e3":("minor: adds \"dealership or garage\"","fail: invents \"customers\", \"public use\"; says Tuesday is \"the following day\""),
 "e4":("pass","fail: turns a past move into an \"upcoming\" one"),
 "e5":("pass (stiff)","fail: invents room facilities and instructions"),
 "f1":("pass","pass"),
 "f2":("pass","pass (raw: `</think>` leak, duplicated text)"),
 "f3":("pass","pass"),
 "f4":("pass","fail: reverses who returns the drill"),
 "f5":("minor: barely friendlier","fail: invents \"you could have been startled\""),
 "p1":("pass","pass (raw: `</think>` leak, duplicated text)"),
 "p2":("pass","pass"),
 "p3":("pass","pass"),
 "p4":("pass","pass"),
 "p5":("pass","fail: speaker now sends the invite instead of asking for one"),
}
g = json.load(open("results/rewrite-gemma4-e2b.json"))["rows"]; l = json.load(open("results/rewrite-lfm3-int4.json"))["rows"]
o = ["# Rewrite outputs, side by side (synthetic prompts)", "",
 "All 20 input messages are made up. Prompt = `GemmaLitertEngine.promptForRewrite(text, tone)` on main @1aee381. ",
 "Sampler = the app's (top-k 10, top-p 0.9, temperature 0.2, seed 0), max 256 output tokens, fresh Engine per prompt, ",
 "LiteRT-LM Python 0.19.1, **box CPU (x86_64, 4 threads), not a phone**. LFM3 ran with `ThinkingConfig(enable_thinking=False)`; ",
 "its raw output is shown, and the verdict judges the text after removing the stray `…</think>` prefix.", "",
 "Verdicts are one rater's (Hax) judgement, not blind, one sample per prompt. Raw JSON: `results/rewrite-*.json`.", ""]
for a, b in zip(g, l):
    o += [f"## {a['id']} · {a['tone']}", "", f"**Input:** {a['input']}", "",
          f"**Gemma 4 E2B:** {a['output']}", "", f"> verdict: {JUDGE[a['id']][0]}", "",
          f"**LFM3-1.7B int4:** {b['output']}", "", f"> verdict: {JUDGE[a['id']][1]}", ""]
open("rewrite-outputs.md", "w").write("\n".join(o))
cnt = lambda k: {v: sum(1 for x in JUDGE.values() if x[k].split(":")[0].split(" (")[0] == v) for v in ("pass","minor","fail")}
print("gemma", cnt(0), "lfm3", cnt(1))
