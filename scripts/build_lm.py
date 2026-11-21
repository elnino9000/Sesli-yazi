# Türkçe sonraki-kelime modeli üretir (derleme sırasında çalışır).
# Kullanım: build_lm.py <corpus.txt> <cikis_klasoru>
import sys, re, collections, subprocess, heapq, os

corpus, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
tmp = os.environ.get("TMPDIR", "/tmp")

def norm(s):
    return s.replace('İ', 'i').replace('I', 'ı').lower()

TOK = re.compile(r"[a-zçğıöşüâîû]+")
SPLIT = re.compile(r"[.?!…;:]+")
APOS = re.compile(r"\s*['’`´]\s*")

def segments(line):
    line = APOS.sub("", norm(line))
    for seg in SPLIT.split(line):
        ws = TOK.findall(seg)
        if ws:
            yield ws

# ---- 1. geçiş: kelime sıklıkları
uni = collections.Counter()
nlines = 0
with open(corpus, encoding='utf-8', errors='ignore') as f:
    for line in f:
        nlines += 1
        for ws in segments(line):
            uni.update(ws)
print("satır:", nlines, "farklı kelime:", len(uni), "toplam:", sum(uni.values()))

def ok(w):
    return len(w) >= 2 or w == 'o'

vocab_list = [(w, c) for w, c in uni.items() if c >= 3 and ok(w)]
vocab_list.sort(key=lambda x: -x[1])
vocab_list = vocab_list[:80000]
vocab = set(w for w, _ in vocab_list)
with open(os.path.join(out, "words_tr.txt"), "w", encoding="utf-8") as f:
    for w, c in vocab_list:
        f.write("%s\t%d\n" % (w, c))
print("sözlük:", len(vocab_list))

# ---- 2. geçiş: ikili ve üçlü diziler
bi_path = os.path.join(tmp, "bi.txt")
tri_path = os.path.join(tmp, "tri.txt")
with open(corpus, encoding='utf-8', errors='ignore') as f, \
        open(bi_path, "w", encoding="utf-8") as fb, open(tri_path, "w", encoding="utf-8") as ft:
    for line in f:
        for ws in segments(line):
            toks = ["^"] + [w if w in vocab else None for w in ws]
            for i in range(1, len(toks)):
                b = toks[i]
                if b is None:
                    continue
                a = toks[i - 1]
                if a is not None:
                    fb.write("%s\t%s\n" % (a, b))
                    if i >= 2 and toks[i - 2] is not None:
                        ft.write("%s %s\t%s\n" % (toks[i - 2], a, b))

def counted(path):
    cmd = "LC_ALL=C sort -S 2G -T %s %s | uniq -c" % (tmp, path)
    p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, text=True, encoding='utf-8')
    for line in p.stdout:
        line = line.rstrip("\n").lstrip()
        sp = line.index(" ")
        cnt = int(line[:sp])
        ctx, w = line[sp + 1:].split("\t")
        yield ctx, w, cnt
    p.wait()

def build(path, name, minc, topk, minTotal, maxCtx):
    heap = []
    cur = None
    items = []

    def flush():
        if cur is None or not items:
            return
        total = sum(c for _, c in items)
        if total < minTotal:
            return
        items.sort(key=lambda x: -x[1])
        line = cur + "\t" + ",".join("%s:%d" % (w, c) for w, c in items[:topk])
        if len(heap) < maxCtx:
            heapq.heappush(heap, (total, line))
        elif total > heap[0][0]:
            heapq.heapreplace(heap, (total, line))

    for ctx, w, c in counted(path):
        if ctx != cur:
            flush()
            cur = ctx
            items = []
        if c >= minc:
            items.append((w, c))
    flush()
    rows = sorted(heap, key=lambda x: -x[0])
    with open(os.path.join(out, name), "w", encoding="utf-8") as f:
        for _, line in rows:
            f.write(line + "\n")
    print(name, "bağlam:", len(rows))

build(bi_path, "lm_bi.txt", 2, 8, 5, 60000)
build(tri_path, "lm_tri.txt", 2, 5, 4, 140000)

# ---------------------------------------------------------------- ölçüm
# Ayrılmış (eğitimde kullanılmayan) cümlelerde tahmin başarısını ölçer ve ağırlıkları dener.
if len(sys.argv) > 3:
    import math, itertools, random
    uni_c = dict(vocab_list)
    mx = max(uni_c.values())
    ulog = math.log(1 + mx)

    def load_lm(name):
        m = {}
        with open(os.path.join(out, name), encoding='utf-8') as f:
            for line in f:
                k, v = line.rstrip("\n").split("\t")
                lst = []
                tot = 0
                for it in v.split(","):
                    w, c = it.rsplit(":", 1)
                    lst.append((w, int(c)))
                    tot += int(c)
                m[k] = (lst, tot)
        return m

    bi = load_lm("lm_bi.txt")
    tri = load_lm("lm_tri.txt")
    vocab_sorted = sorted(vocab)
    by_prefix = {}
    for w in sorted(vocab_sorted, key=lambda x: -uni_c[x]):
        for n in (1, 2):
            if len(w) > n:
                lst = by_prefix.setdefault(w[:n], [])
                if len(lst) < 400:
                    lst.append(w)

    tests = []
    with open(sys.argv[3], encoding='utf-8', errors='ignore') as f:
        for line in f:
            for ws in segments(line):
                ws = [w if w in vocab else None for w in ws]
                if len(ws) >= 3:
                    tests.append(ws)
    random.seed(7)
    random.shuffle(tests)
    tests = tests[:12000]
    print("ölçüm cümlesi:", len(tests))

    def ctx_scores(p2, p1, wbi, wtri):
        acc = {}
        if p2 is not None:
            e = tri.get(p2 + " " + p1)
            if e:
                for w, c in e[0]:
                    acc[w] = acc.get(w, 0) + wtri * c / e[1]
        e = bi.get(p1)
        if e:
            for w, c in e[0]:
                acc[w] = acc.get(w, 0) + wbi * c / e[1]
        return acc

    def evaluate(wbi, wtri, kctx, wuni, nprefix):
        hit = tot = 0
        for ws in (tests if nprefix == 0 else tests[:1500]):
            toks = ["^"] + ws
            for i in range(1, len(toks)):
                actual = toks[i]
                if actual is None:
                    continue
                p1 = toks[i - 1]
                if p1 is None:
                    continue
                p2 = toks[i - 2] if i >= 2 else None
                if i == 1:
                    p2 = None
                if i >= 2 and p2 is None:
                    continue
                if i >= 2 and toks[i - 2] is None:
                    continue
                acc = ctx_scores(p2, p1, wbi, wtri)
                if nprefix == 0:
                    cand = acc
                    scored = sorted(cand.items(), key=lambda x: -x[1])[:3]
                    top = [w for w, _ in scored]
                else:
                    if len(actual) <= nprefix:
                        continue
                    pre = actual[:nprefix]
                    cands = by_prefix.get(pre, [])
                    best = []
                    for w in cands:
                        s = wuni * math.log(1 + uni_c[w]) / ulog + kctx * acc.get(w, 0)
                        best.append((s, w))
                    best.sort(reverse=True)
                    top = [w for _, w in best[:3]]
                tot += 1
                if actual in top:
                    hit += 1
        return hit, tot

    h0, t0 = evaluate(0.45, 1.0, 3.0, 0.6, 0)
    print("SONRAKİ KELİME ilk3 isabet: %.1f%% (%d/%d)" % (100.0 * h0 / max(1, t0), h0, t0))
    for npfx in (1, 2):
        best = None
        for wbi, kctx, wuni in itertools.product((0.3, 0.6), (8.0, 16.0, 32.0, 64.0), (0.05, 0.15, 0.3)):
            h1, t1 = evaluate(wbi, 1.0, kctx, wuni, npfx)
            r = h1 / max(1, t1)
            if best is None or r > best[0]:
                best = (r, wbi, kctx, wuni)
            print("  önek=%d wbi=%.1f k=%.1f wuni=%.1f -> %.1f%%" % (npfx, wbi, kctx, wuni, 100 * r))
        print("EN İYİ önek=%d: %.1f%% wbi=%.1f k=%.1f wuni=%.1f" % (npfx, 100 * best[0], best[1], best[2], best[3]))
