"""Box-CPU (x86_64) feasibility test of EmbeddingGemma 2 audio encoder + eg2-transcribe reader v2.
NOT a phone. Clips <= 9.96 s only (10 s window files). Audio: LibriSpeech dev-clean dummy subset (CC BY 4.0), local only.
Front-end written from the eg2-transcribe docs (Apache-2.0): 16 kHz, 160-sample semicausal pad, periodic Hann 320,
|rfft 512|, 128 HTK mel 0-8000 Hz (no norm), log(mel + 1e-3)."""
import io, sys, time, json, re, resource, numpy as np, soundfile as sf, pyarrow.parquet as pq
from ai_edge_litert.interpreter import Interpreter
D = __import__("os").environ.get("EG2T_DIR", "eg2t") + "/"
F, HOP, NFFT, NMEL = 320, 160, 512, 128
def hz2mel(f): return 2595.0*np.log10(1.0+f/700.0)
def mel2hz(m): return 700.0*(10.0**(m/2595.0)-1.0)
def filters():
    n = NMEL+2; mmax = hz2mel(8000.0); f = mel2hz(np.linspace(0, mmax, n)); hz = np.arange(NFFT//2+1)*8000.0/256
    down = (hz[None,:]-f[:-2,None])/(f[1:-1,None]-f[:-2,None]); up = (f[2:,None]-hz[None,:])/(f[2:,None]-f[1:-1,None])
    return np.maximum(0, np.minimum(down, up))  # [128, 257]
FB = filters(); WIN = (0.5-0.5*np.cos(2*np.pi*np.arange(F)/F)).astype(np.float32)
def logmel(x):
    n = len(x); frames = 0 if n < F//2+1 else (n-(F//2+1))//HOP+1
    xp = np.concatenate([np.zeros(HOP, np.float32), x.astype(np.float32), np.zeros(F, np.float32)])
    idx = np.arange(frames)[:,None]*HOP + np.arange(F)[None,:]
    fr = xp[idx]*WIN
    mag = np.abs(np.fft.rfft(fr.astype(np.float64), NFFT, axis=1))
    return np.log(mag @ FB.T + 1e-3).astype(np.float32), frames
def load(path, sig):
    it = Interpreter(model_path=path, num_threads=4); r = it.get_signature_runner(sig); return it, r
vocab = open(D+"vocab.txt").read().split("\n")
t0 = time.perf_counter(); _, enc = load(D+"eg2_audio_f16safe_fp16w_w10.tflite", "audio_10s"); _, rd = load(D+"reader_w10.tflite", "reader_10s"); load_s = time.perf_counter()-t0
def norm(s): return re.sub(r"[^a-z' ]", " ", s.lower()).split()
def wer(ref, hyp):
    d = np.arange(len(hyp)+1)
    for i, r in enumerate(ref, 1):
        prev, d[0] = d.copy(), i
        for j, h in enumerate(hyp, 1): d[j] = min(prev[j]+1, d[j-1]+1, prev[j-1]+(r != h))
    return d[-1], len(ref)
t = pq.read_table(D+"ls_dummy.parquet").to_pylist()
rows = []; errs = refs = 0; audio_s = proc_s = 0.0
for r in t:
    x, sr = sf.read(io.BytesIO(r["audio"]["bytes"]), dtype="float32"); assert sr == 16000
    if len(x)/sr > 9.96: continue
    s = time.perf_counter()
    mel, frames = logmel(x); m = np.zeros((1,1000,128), np.float32); m[0,:frames] = mel[:1000]
    mask = np.zeros((1,1000), np.float32); mask[0,:frames] = 1
    eo = enc(mel=m, mask=mask); soft, smask = eo["soft"], eo["mask"]
    ro = rd(soft=soft.astype(np.float32), mask=smask.astype(np.float32))
    ids, best, blank = ro["ids"][0], ro["best"][0], ro["blank"][0]
    valid = int(smask[0].sum()); words = []; prev = -1
    for i in range(valid):
        w = -1 if blank[i] > best[i] else int(ids[i])
        if w != -1 and w != prev: words.append(vocab[w])
        prev = w
    el = time.perf_counter()-s
    e, n = wer(norm(r["text"]), [w.lower() for w in words]); errs += e; refs += n; audio_s += len(x)/sr; proc_s += el
    rows.append({"id": r["id"], "sec": len(x)/sr, "ms": el*1000, "ref": r["text"].lower(), "hyp": " ".join(words), "errors": int(e), "words": n})
res = {"clips": len(rows), "audio_s": audio_s, "proc_s": proc_s, "x_realtime": audio_s/proc_s, "wer": errs/refs, "load_s": load_s,
       "peak_rss_mib": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1024, "rows": rows}
json.dump(res, open("results/transcribe-box.json","w"), indent=1)
print(json.dumps({k:v for k,v in res.items() if k!="rows"})); [print(x["hyp"], "||", x["ref"]) for x in rows[:4]]
