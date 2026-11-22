# Türkçe sonraki-kelime modeli üretir (derleme sırasında çalışır).
# Kullanım: build_lm.py <corpus.txt> <cikis_klasoru>
import sys, re, collections, subprocess, heapq, os

corpus, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
tmp = os.environ.get("TMPDIR", "/tmp")

def norm(s):
    return s.replace('İ', 'i').replace('I', 'ı').lower()


# ---- klavye komşulukları ve düzeltme biçimleri (hem temizlikte hem ölçümde kullanılır)
import itertools
ROWS = ["qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"]
NB = {}
def _addnb(a, b):
    NB.setdefault(a, set()).add(b); NB.setdefault(b, set()).add(a)
for _r in ROWS:
    for _i in range(len(_r) - 1):
        _addnb(_r[_i], _r[_i + 1])
for _u, _l in ((ROWS[0], ROWS[1]), (ROWS[1], ROWS[2])):
    for _j in range(len(_l)):
        for _jj in (_j, _j + 1):
            if _jj < len(_u):
                _addnb(_l[_j], _u[_jj])
LETTERS = "abcçdefgğhıijklmnoöprsştuüvyz"
DIA = {'c': 'ç', 'g': 'ğ', 'i': 'ı', 'o': 'ö', 's': 'ş', 'u': 'ü'}

def dia_combos(p):
    pos = [i for i, ch in enumerate(p) if ch in DIA]
    res = []
    if len(pos) > 6:
        return res
    for r in range(1, len(pos) + 1):
        for sub in itertools.combinations(pos, r):
            lst = list(p)
            for i in sub:
                lst[i] = DIA[lst[i]]
            res.append(("".join(lst), r))
    return res

def variants(p):
    n = len(p)
    res = []
    for i in range(n):
        for ch in NB.get(p[i], ()):
            res.append((p[:i] + ch + p[i + 1:], 'sub'))
    for i in range(n - 1):
        if p[i] != p[i + 1]:
            res.append((p[:i] + p[i + 1] + p[i] + p[i + 2:], 'tr'))
    if n >= 4:
        for i in range(n):
            res.append((p[:i] + p[i + 1:], 'del'))
    for i in range(n + 1):
        for ch in LETTERS:
            res.append((p[:i] + ch + p[i:], 'ins'))
    return res

NORM = {}   # hatalı yazım -> doğru yazım (derlemeden öğrenilir)

TOK = re.compile(r"[a-zçğıöşüâîû]+")
SPLIT = re.compile(r"[.?!…;:]+")
APOS = re.compile(r"\s*['’`´]\s*")

def segments(line):
    line = APOS.sub("", norm(line))
    for seg in SPLIT.split(line):
        ws = TOK.findall(seg)
        if ws:
            if NORM:
                ws = [NORM.get(w, w) for w in ws]
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

# ---- derlem temizliği: altyazılardaki harfsiz yazımları ve yazım hatalarını doğrusuna bağla
def resolve(w):
    for _ in range(4):
        n = NORM.get(w)
        if n is None or n == w:
            return w
        w = n
    return w

asc = 0
for w, c in list(uni.items()):
    best = None
    for v, n in dia_combos(w):
        cv = uni.get(v, 0)
        if cv >= 20 and cv >= 4 * c and (best is None or cv > best[1]):
            best = (v, cv)
    if best:
        NORM[w] = best[0]
        asc += 1
print("harfsiz yazımdan doğrusuna bağlanan:", asc)

typo = 0
examples = []
for w, c in list(uni.items()):
    if c < 3 or c > 3000 or w in NORM or len(w) < 3:
        continue
    best = None
    for v, kind in variants(w):
        cv = uni.get(v, 0)
        if cv >= 100 and cv >= 25 * c and (best is None or cv > best[1]):
            best = (v, cv)
    if best:
        NORM[w] = best[0]
        typo += 1
        if len(examples) < 25 and c > 20:
            examples.append("%s>%s" % (w, best[0]))
print("yazım hatası olarak bağlanan:", typo, " örnek:", " ".join(examples))

uni2 = collections.Counter()
for w, c in uni.items():
    uni2[resolve(w)] += c
uni = uni2
for w in list(NORM):
    NORM[w] = resolve(w)

vocab_list = [(w, c) for w, c in uni.items() if c >= 4 and ok(w)]
vocab_list.sort(key=lambda x: -x[1])
vocab_all = vocab_list[:330000]
vocab_list = vocab_list[:100000]
vocab = set(w for w, _ in vocab_list)
with open(os.path.join(out, "words_tr.txt"), "w", encoding="utf-8") as f:
    for w, c in vocab_all:
        f.write("%s\t%d\n" % (w, c))
print("sözlük:", len(vocab_all), "n-gram sözlüğü:", len(vocab_list))

# ---- 2. geçiş: ikili ve üçlü diziler
bi_path = os.path.join(tmp, "bi.txt")
tri_path = os.path.join(tmp, "tri.txt")
four_path = os.path.join(tmp, "four.txt")
skip_path = os.path.join(tmp, "skip.txt")
with open(corpus, encoding='utf-8', errors='ignore') as f, \
        open(bi_path, "w", encoding="utf-8") as fb, open(tri_path, "w", encoding="utf-8") as ft, \
        open(four_path, "w", encoding="utf-8") as f4, open(skip_path, "w", encoding="utf-8") as fs:
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
    four = {}
    skip = {}

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

    WT = {'bi': 0.45, 'tri': 1.0, 'four': 0.0, 'skip': 0.0}

    def add_lm(acc, e, wt):
        if e and wt:
            for w, c in e[0]:
                acc[w] = acc.get(w, 0) + wt * c / e[1]

    def ctx_list(hist):
        """hist: önceki kelimeler (en yeni sonda, cümle başı '^' ile başlar)"""
        acc = {}
        if not hist:
            return acc
        add_lm(acc, bi.get(hist[-1]), WT['bi'])
        if len(hist) >= 2:
            add_lm(acc, tri.get(hist[-2] + " " + hist[-1]), WT['tri'])
            add_lm(acc, skip.get(hist[-2]), WT['skip'])
        if len(hist) >= 3:
            add_lm(acc, four.get(" ".join(hist[-3:])), WT['four'])
        return acc

    def ctx_scores(p2, p1):
        h = []
        if p2 is not None:
            h.append(p2)
        h.append(p1)
        return ctx_list(h)

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

    # ---- (1) sonraki kelime (harf yazılmadan): 4'lü ve atlamalı modellerin katkısını ölç
    def nw_eval(n_sent):
        hit = tot = 0
        for ws in TESTS[:n_sent]:
            toks = ["^"] + ws
            for i in range(1, len(toks)):
                if toks[i] is None or any(t is None for t in toks[max(0, i - 3):i]):
                    continue
                acc = ctx_list(toks[max(0, i - 3):i])
                top = [w for w, _ in sorted(acc.items(), key=lambda x: -x[1])[:3]]
                tot += 1
                hit += toks[i] in top
        return 100.0 * hit / max(1, tot), tot

    bestw = None
    for wb in (0.3, 0.6):
        for w4 in (0.0,):
            for wsk in (0.0,):
                WT.update({'bi': wb, 'tri': 1.0, 'four': w4, 'skip': wsk})
                r, n = nw_eval(3000)
                print("  sonraki kelime bi=%.2f dörtlü=%.1f atlamalı=%.2f -> %.1f%% (%d)" % (wb, w4, wsk, r, n))
                if bestw is None or r > bestw[0]:
                    bestw = (r, wb, w4, wsk)
    WT.update({'bi': bestw[1], 'tri': 1.0, 'four': bestw[2], 'skip': bestw[3]})
    r, n = nw_eval(6000)
    print("SONRAKİ KELİME ilk3 isabet (en iyi bi=%.2f dörtlü=%.1f atlamalı=%.2f): %.1f%% (%d)" % (bestw[1], bestw[2], bestw[3], r, n))

    # ---- (2) gerçek yazım hatası düzeltme hattı: bilinmeyen kelime, bağlama uymayan geçerli kelime
    #          ve iki yanyana kelimeye bakarak geriye dönük düzeltme
    DIA = {'c': '\u00e7', 'g': '\u011f', 'i': '\u0131', 'o': '\u00f6', 's': '\u015f', 'u': '\u00fc'}
    ASCIIFY = {v: k for k, v in DIA.items()}
    import itertools

    def dia_combos(p):
        pos = [i for i, ch in enumerate(p) if ch in DIA]
        res = []
        if len(pos) > 6:
            return res
        for r in range(1, len(pos) + 1):
            for sub in itertools.combinations(pos, r):
                lst = list(p)
                for i in sub:
                    lst[i] = DIA[lst[i]]
                res.append(("".join(lst), r))
        return res

    EXACT = {}
    def exact_variants(t):
        """t'ye yakın, sözlükte olan kelimeler: (kelime, tür, değişen harf sayısı)"""
        r = EXACT.get(t)
        if r is not None:
            return r
        r = []
        seen = set()
        for v, kind in variants(t):
            if v in uni_c and uni_c[v] >= 100 and v != t and v not in seen:
                seen.add(v); r.append((v, kind, 1))
        for v, n in dia_combos(t):
            if v in uni_c and uni_c[v] >= 100 and v != t and v not in seen:
                seen.add(v); r.append((v, 'dia', n))
        EXACT[t] = r
        return r

    def usc(w):
        return WUNI * math.log(1 + uni_c[w]) / ulog if w in uni_c else -1.0

    def pair_p(nxt, w):
        e = bi.get(w)
        if not e or nxt is None:
            return 0.0
        for x, c in e[0]:
            if x == nxt:
                return c / e[1]
        return 0.0

    def best_fix(t, acc, nxt, P):
        """(en iyi kelime, puan farkı). Bilinmeyen kelimede fark çok büyük sayılır."""
        known = t in uni_c
        st = (usc(t) + KCTX * acc.get(t, 0) + KCTX * pair_p(nxt, t)) if known else None
        best = None
        for v, kind, n in exact_variants(t):
            cost = P['dia'] * n if kind == 'dia' else P['pen'] * (1.0 if kind == 'sub' else 1.2 if kind == 'tr' else 1.6)
            sv = usc(v) + KCTX * acc.get(v, 0) + KCTX * pair_p(nxt, v) - cost
            if best is None or sv > best[1]:
                best = (v, sv)
        if best is None:
            return None, 0.0
        if st is None:
            return best[0], 99.0
        return best[0], best[1] - st

    def corrupt_word(w, rnd):
        n = len(w)
        kinds = ['sub', 'sub', 'tr', 'del', 'ins']
        if any(ch in ASCIIFY for ch in w):
            kinds += ['asc', 'asc', 'asc']
        kind = rnd.choice(kinds)
        i = rnd.randrange(1, n) if n > 1 else 0
        if kind == 'asc':
            t = "".join(ASCIIFY.get(ch, ch) for ch in w)
            return t if t != w else None
        if kind == 'sub' and w[i] in NB:
            return w[:i] + rnd.choice(sorted(NB[w[i]])) + w[i + 1:]
        if kind == 'tr' and i + 1 < n and w[i] != w[i + 1]:
            return w[:i] + w[i + 1] + w[i] + w[i + 2:]
        if kind == 'del' and n >= 4:
            return w[:i] + w[i + 1:]
        if kind == 'ins':
            return w[:i] + rnd.choice(sorted(NB.get(w[i], {w[i]}))) + w[i:]
        return None

    rnd = random.Random(5)
    SENT = []
    for ws in TESTS[:3000]:
        if None in ws or len(ws) < 3 or len(ws) > 12:
            continue
        typed = []
        for w in ws:
            t = None
            if len(w) >= 3 and rnd.random() < 0.2:
                t = corrupt_word(w, rnd)
            typed.append(t if (t is not None and t != w) else w)
        SENT.append((ws, typed))
        if len(SENT) >= 700:
            break
    print("hat örnek cümle:", len(SENT))

    FAILS = []

    def run(P, use_real, use_retro, policy='skip', dump=False):
        ok_c = n_c = bad_clean = n_clean = 0
        nw_hit = nw_tot = 0
        for ws, typed in SENT:
            hist = ["^"]          # bağlam için kullanılan kelimeler
            res = []
            for i, t in enumerate(typed):
                acc = ctx_list(hist[-3:])
                # bağlamdan sonraki kelime tahmini (yazım hatalı bağlamla)
                top = [w for w, _ in sorted(acc.items(), key=lambda x: -x[1])[:3]]
                nw_tot += 1
                nw_hit += ws[i] in top
                fixed = t
                if t not in uni_c:
                    v, d = best_fix(t, acc, None, P)
                    if v:
                        fixed = v
                elif use_real and len(t) >= 3:
                    v, d = best_fix(t, acc, None, P)
                    if v and d > P['m_real'] and acc.get(v, 0) > 0:
                        fixed = v
                res.append(fixed)
                if policy == 'raw' or fixed in uni_c:
                    hist.append(fixed)
                if use_retro and len(res) >= 2:
                    wprev = res[-2]
                    acc2 = ctx_list(hist[-4:-2] if len(hist) >= 4 else hist[:-1])
                    v, d = best_fix(wprev, acc2, fixed, P)
                    if v and d > P['m_retro'] and len(wprev) >= 3:
                        res[-2] = v
            for w, t, r in zip(ws, typed, res):
                if t != w:
                    n_c += 1; ok_c += (r == w)
                    if dump and r != w and len(FAILS) < 40:
                        FAILS.append((w, t, r, " ".join(typed)))
                else:
                    n_clean += 1; bad_clean += (r != w)
                    if dump and r != w and len(FAILS) < 80:
                        FAILS.append((w, t, r, " ".join(typed)))
        return (100.0 * ok_c / max(1, n_c), 100.0 * bad_clean / max(1, n_clean), n_c, n_clean,
                100.0 * nw_hit / max(1, nw_tot))

    base_ok = None
    P0 = {'pen': 0.1, 'dia': 0.03, 'm_real': 1e9, 'm_retro': 1e9}
    r0 = run(P0, False, False, 'raw')
    print("YALNIZ BİLİNMEYEN: hatalı kelimeyi doğru bulma %.1f%%  doğru kelimeyi bozma %.2f%%  (hatalı %d, doğru %d) | hatalı bağlamla sonraki kelime %.1f%%" % r0)
    r1 = run(P0, False, False, 'skip')
    print("BAĞLAM HATAYI ATLASIN: sonraki kelime %.1f%% (hatalı kelimeyi bağlama katarsa: %.1f%%)" % (r1[4], r0[4]))
    best = None
    for pen in (0.05, 0.1, 0.2):
        for dia in (0.02, 0.05):
            for mr in (0.4, 0.8, 1.6):
                for mt in (0.4, 0.8, 1.6, 1e9):
                    P = {'pen': pen, 'dia': dia, 'm_real': mr, 'm_retro': mt}
                    ok, bad, nc, ncl, nwh = run(P, True, mt < 1e8)
                    score = ok - 30.0 * bad
                    print("  pen=%.2f dia=%.2f gerçek=%.1f geri=%s -> düzelt %.1f%%  bozma %.2f%%" % (pen, dia, mr, ("%.1f" % mt) if mt < 1e8 else "kapalı", ok, bad))
                    if best is None or score > best[0]:
                        best = (score, P, ok, bad)
    print("EN İYİ:", best[1], "düzelt %.1f%% bozma %.2f%%" % (best[2], best[3]))
    P = best[1]
    run(P, True, P['m_retro'] < 1e8, 'skip', dump=True)
    print("--- KALAN HATALAR (doğru, yazılan, çıkan | cümle) ---")
    for w, t, r, sent in FAILS[:60]:
        print("  %s | %s -> %s | %s" % (w, t, r, sent[:90]))

    # ------------------------------------------------------------ ağır yazım hatası (6-7 harf yanlış)
    DIAP = {'c': 'ç', 'g': 'ğ', 'i': 'ı', 'o': 'ö', 's': 'ş', 'u': 'ü'}
    POS = {}
    for _ri, _row in enumerate(ROWS):
        for _ci, _ch in enumerate(_row):
            POS[_ch] = (_ci + (0.0, 0.5, 1.0)[_ri], _ri)
    VOW = "aeıioöuü"
    def kdist(a, b):
        if a not in POS or b not in POS:
            return 99.0
        return math.hypot(POS[a][0] - POS[b][0], POS[a][1] - POS[b][1])
    def sub_cost(a, b):
        if a == b:
            return 0
        if b in NB.get(a, ()):
            return 40
        if DIAP.get(a) == b or DIAP.get(b) == a:
            return 15
        if a in VOW and b in VOW:
            return 65
        if kdist(a, b) <= 2.3:
            return 70
        return 100
    def wdist(t, w, limit):
        n, m = len(t), len(w)
        prev2 = None
        prev = [j * 70 for j in range(m + 1)]
        for i in range(1, n + 1):
            cur = [i * 70] + [0] * m
            rowmin = cur[0]
            ti = t[i - 1]
            for j in range(1, m + 1):
                c = prev[j - 1] + sub_cost(ti, w[j - 1])
                d = prev[j] + 70
                if d < c: c = d
                d = cur[j - 1] + 70
                if d < c: c = d
                if i > 1 and j > 1 and ti == w[j - 2] and t[i - 2] == w[j - 1]:
                    d = prev2[j - 2] + 60
                    if d < c: c = d
                cur[j] = c
                if c < rowmin: rowmin = c
            if rowmin > limit:
                return 10 ** 6
            prev2, prev = prev, cur
        return prev[m]

    def garble(w, rnd, q):
        out_c = []
        for ch in w:
            r = rnd.random()
            if r < q * 0.5 and ch in NB:
                out_c.append(rnd.choice(sorted(NB[ch])))
            elif r < q * 0.65 and ch in POS:
                near = [x for x in POS if x != ch and kdist(ch, x) <= 2.3]
                out_c.append(rnd.choice(sorted(near)) if near else ch)
            elif r < q * 0.75 and ch in VOW:
                out_c.append(rnd.choice("aeıioöuü"))
            elif r < q:
                out_c.append(rnd.choice(LETTERS))
            elif r < q + 0.04:
                continue
            else:
                out_c.append(ch)
        return "".join(out_c)

    pool = sorted(allw, key=lambda x: -uni_c[x])[:30000]
    rnd2 = random.Random(11)
    HW = []
    for ws in TESTS[:6000]:
        if None in ws or len(ws) < 3 or len(ws) > 12:
            continue
        i = rnd2.randrange(1, len(ws))
        if len(ws[i]) < 4 or len(ws[i]) > 12:
            continue
        g = garble(ws[i], rnd2, 0.5)
        if g == ws[i] or len(g) < 3:
            continue
        HW.append((ws[:i], ws[i], g))
        if len(HW) >= 250:
            break
    print("ağır hatalı örnek:", len(HW))
    DATA = []
    for hist, w, g in HW:
        acc = ctx_list((["^"] + hist)[-3:])
        accn = {}
        cl = len(g)
        c0 = g[0]
        okf = set([c0]) | set(NB.get(c0, ())) | set([DIAP.get(c0, c0)])
        dl = []
        for x in pool:
            if abs(len(x) - cl) > 3 or x[0] not in okf:
                continue
            d = wdist(g, x, 65 * cl)
            if d < 10 ** 6:
                dl.append((x, d))
        for x in acc:
            if x in uni_c and x not in dict(dl):
                d = wdist(g, x, 80 * cl)
                if d < 10 ** 6:
                    dl.append((x, d))
        DATA.append((w, g, acc, dl))
    def fz_eval(FK, LIM, use_ctx, margin, KC=12.0):
        t1 = t3 = acc_n = acc_ok = 0
        for w, g, acc, dl in DATA:
            cl = len(g)
            sc = []
            for x, d in dl:
                cx = acc.get(x, 0) if use_ctx else 0
                if d > LIM * cl and cx <= 0:
                    continue
                sc.append((WUNI * math.log(1 + uni_c[x]) / ulog + KC * cx - 0.002 * len(x) - d / 100.0 * FK, x))
            sc.sort(reverse=True)
            top = [x for _, x in sc[:3]]
            t1 += bool(top) and top[0] == w
            t3 += w in top
            if len(sc) >= 1:
                gap = sc[0][0] - (sc[1][0] if len(sc) > 1 else -99)
                if gap >= margin:
                    acc_n += 1
                    acc_ok += sc[0][1] == w
        n = max(1, len(DATA))
        return 100.0 * t1 / n, 100.0 * t3 / n, 100.0 * acc_n / n, 100.0 * acc_ok / max(1, acc_n)
    bestf = None
    for FK in (3.5, 6.0, 10.0):
        for KC in (6.0, 12.0, 24.0, 48.0):
            r = fz_eval(FK, 60, True, 99, KC)
            print("  AĞIR HATA FK=%.1f KC=%.0f -> ilk1 %.1f%%  ilk3 %.1f%%" % (FK, KC, r[0], r[1]))
            if bestf is None or r[1] + r[0] > bestf[0]:
                bestf = (r[0] + r[1], FK, KC)
    _, FKb, KCb = bestf
    LIMb = 60
    fzm = 99.0
    for mg in (0.0, 0.5, 1.0, 2.0, 3.0, 5.0, 8.0):
        r = fz_eval(FKb, LIMb, True, mg, KCb)
        print("  OTOMATİK DÜZELTME marj=%.1f: kapsam %.1f%% doğruluk %.1f%%" % (mg, r[2], r[3]))
        if r[3] >= 85 and fzm == 99.0:
            fzm = mg
    print("AĞIR HATA SEÇİLEN: FK=%.1f KC=%.0f marj=%.1f" % (FKb, KCb, fzm))
    with open(os.path.join(out, "params.txt"), "w") as f:
        f.write("pen=%.3f\ndia=%.3f\nreal=%.3f\nretro=%.3f\n" % (P['pen'], P['dia'], P['m_real'], min(P['m_retro'], 99.0)))
        f.write("wbi=%.3f\nwfour=%.3f\nwskip=%.3f\n" % (WT['bi'], WT['four'], WT['skip']))
        f.write("fzk=%.3f\nfzlim=%d\nfzm=%.3f\nfzc=%.3f\n" % (FKb, LIMb, fzm, KCb))
