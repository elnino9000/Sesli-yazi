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
vocab_all = [(w, c) for w, c in vocab_list if c >= 4][:250000]
vocab_list = vocab_list[:100000]
vocab = set(w for w, _ in vocab_list)
with open(os.path.join(out, "words_tr.txt"), "w", encoding="utf-8") as f:
    for w, c in vocab_all:
        f.write("%s\t%d\n" % (w, c))
print("sözlük:", len(vocab_all), "n-gram sözlüğü:", len(vocab_list))

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

# ---------------------------------------------------------------- ölçüm ve ayar
# Eğitimde kullanılmayan cümlelerde (1) sonraki kelime, (2) yanlış basılan harflerden
# doğru kelimeyi bulma başarısını ölçer; en iyi ağırlıkları params.txt'ye yazar.
if len(sys.argv) > 3:
    import math, random, bisect
    uni_c = dict(vocab_all)
    for w, c in vocab_list:
        uni_c.setdefault(w, c)
    mx = max(uni_c.values())
    ulog = math.log(1 + mx)
    allw = sorted(uni_c)

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

    # Türkçe Q klavye komşulukları (Java ile aynı)
    ROWS = ["qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"]
    NB = {}
    def addnb(a, b):
        NB.setdefault(a, set()).add(b); NB.setdefault(b, set()).add(a)
    for r in ROWS:
        for i in range(len(r) - 1):
            addnb(r[i], r[i + 1])
    for u, l in ((ROWS[0], ROWS[1]), (ROWS[1], ROWS[2])):
        for j in range(len(l)):
            for jj in (j, j + 1):
                if jj < len(u):
                    addnb(l[j], u[jj])
    LETTERS = "abcçdefgğhıijklmnoöprsştuüvyz"

    # 3-4 harfli önekler için sık kelime listesi
    top_by_prefix = {}
    for w in sorted(allw, key=lambda x: -uni_c[x]):
        for n in (3, 4):
            if len(w) >= n:
                lst = top_by_prefix.setdefault(w[:n], [])
                if len(lst) < 60:
                    lst.append(w)

    def range_words(v, cap):
        if len(v) in (3, 4):
            return top_by_prefix.get(v, [])
        i = bisect.bisect_left(allw, v)
        res = []
        while i < len(allw) and allw[i].startswith(v) and len(res) < cap:
            res.append(allw[i]); i += 1
        return res

    def ctx_scores(p2, p1):
        acc = {}
        if p2 is not None:
            e = tri.get(p2 + " " + p1)
            if e:
                for w, c in e[0]:
                    acc[w] = acc.get(w, 0) + c / e[1]
        e = bi.get(p1)
        if e:
            for w, c in e[0]:
                acc[w] = acc.get(w, 0) + 0.45 * c / e[1]
        return acc

    KCTX, WUNI = 12.0, 0.2

    def variants(p):
        n = len(p)
        out_v = []
        for i in range(n):
            for ch in NB.get(p[i], ()):
                out_v.append((p[:i] + ch + p[i + 1:], 'sub'))
        for i in range(n - 1):
            if p[i] != p[i + 1]:
                out_v.append((p[:i] + p[i + 1] + p[i] + p[i + 2:], 'tr'))
        if n >= 4:
            for i in range(n):
                out_v.append((p[:i] + p[i + 1:], 'del'))
        for i in range(n + 1):
            for ch in LETTERS:
                out_v.append((p[:i] + ch + p[i:], 'ins'))
        return out_v

    def predict(p, acc, pen, fix):
        best = {}
        def consider(w, cost):
            s = WUNI * math.log(1 + uni_c[w]) / ulog + KCTX * acc.get(w, 0) - 0.002 * len(w) - cost
            if w not in best or s > best[w]:
                best[w] = s
        if p in uni_c:
            consider(p, 0.0)
        for w in range_words(p, 400):
            if len(w) > len(p):
                consider(w, 0.0)
        if fix and len(p) >= 3:
            for v, kind in variants(p):
                if len(v) < 3:
                    continue
                for w in range_words(v, 120):
                    consider(w, pen[kind])
        return [w for w, _ in sorted(best.items(), key=lambda x: -x[1])[:3]]

    TESTS = []
    with open(sys.argv[3], encoding='utf-8', errors='ignore') as f:
        for line in f:
            for ws in segments(line):
                ws = [w if w in vocab else None for w in ws]
                if len(ws) >= 3:
                    TESTS.append(ws)
    random.seed(7)
    random.shuffle(TESTS)
    print("ölçüm cümlesi:", len(TESTS))

    # ---- (1) sonraki kelime (harf yazılmadan)
    hit = tot = 0
    for ws in TESTS[:6000]:
        toks = ["^"] + ws
        for i in range(1, len(toks)):
            if toks[i] is None or toks[i - 1] is None:
                continue
            p2 = toks[i - 2] if i >= 2 else None
            if i >= 2 and p2 is None:
                continue
            acc = ctx_scores(p2, toks[i - 1])
            top = [w for w, _ in sorted(acc.items(), key=lambda x: -x[1])[:3]]
            tot += 1
            hit += toks[i] in top
    print("SONRAKİ KELİME ilk3 isabet: %.1f%% (%d/%d)" % (100.0 * hit / max(1, tot), hit, tot))

    # ---- (2) yanlış basış düzeltme
    def corrupt(w, rnd):
        n = len(w)
        kind = rnd.choice(['sub', 'sub', 'tr', 'del', 'ins'])
        i = rnd.randrange(1, n) if n > 1 else 0
        if kind == 'sub' and w[i] in NB:
            return w[:i] + rnd.choice(sorted(NB[w[i]])) + w[i + 1:]
        if kind == 'tr' and i + 1 < n and w[i] != w[i + 1]:
            return w[:i] + w[i + 1] + w[i] + w[i + 2:]
        if kind == 'del' and n >= 5:
            return w[:i] + w[i + 1:]
        if kind == 'ins':
            return w[:i] + rnd.choice(sorted(NB.get(w[i], {w[i]}))) + w[i:]
        return None

    rnd = random.Random(11)
    cases = []
    for ws in TESTS[:4000]:
        toks = ["^"] + ws
        for i in range(2, len(toks)):
            a = toks[i]
            if a is None or len(a) < 4 or toks[i - 1] is None or toks[i - 2] is None:
                continue
            t = corrupt(a, rnd)
            if t is None or t == a:
                continue
            acc = ctx_scores(toks[i - 2], toks[i - 1])
            cases.append((a, t, acc))
            if len(cases) >= 2500:
                break
        if len(cases) >= 2500:
            break
    print("yanlış basış örneği:", len(cases))

    def rate(pen, fix, mode):
        h = 0
        for a, t, acc in cases:
            if mode == 'full':
                p = t
            elif mode == 'part':
                p = t[:max(3, int(len(t) * 0.7))]
            else:
                p = a
            h += a in predict(p, acc, pen, fix)
        return 100.0 * h / len(cases)

    base_full = rate(None, False, 'full')
    base_part = rate(None, False, 'part')
    print("DÜZELTME YOK : yanlış-tam %.1f%%  yanlış-yarım %.1f%%  doğru-tam %.1f%%" %
          (base_full, base_part, rate(None, False, 'clean')))
    bestp = None
    for pu in (0.1, 0.25, 0.5, 1.0, 2.0):
        pen = {'sub': pu, 'tr': pu * 1.2, 'del': pu * 1.6, 'ins': pu * 1.6}
        f = rate(pen, True, 'full'); pa = rate(pen, True, 'part'); c = rate(pen, True, 'clean')
        score = f + 0.5 * pa + c
        print("  pen=%.2f: yanlış-tam %.1f%%  yanlış-yarım %.1f%%  doğru-tam %.1f%%" % (pu, f, pa, c))
        if bestp is None or score > bestp[0]:
            bestp = (score, pu, f, pa, c)
    print("EN İYİ pen=%.2f: yanlış-tam %.1f%% yarım %.1f%% doğru %.1f%%" % bestp[1:])
    with open(os.path.join(out, "params.txt"), "w") as f:
        f.write("pen=%.3f\n" % bestp[1])
