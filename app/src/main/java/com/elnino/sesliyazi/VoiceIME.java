package com.elnino.sesliyazi;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sesli Klavye.
 *
 * Klavye olarak seçilince kendiliğinden dinler; söylenenleri odaktaki yazı kutusuna yazar.
 * Klavye olduğu için her uygulamada çalışır (başka uygulamalar klavyeyi gizleyemez).
 *
 * Güvenilirlik için:
 *  - Birden fazla konuşma tanıyıcı sırayla denenir (sistem varsayılanı, Google, diğerleri).
 *  - Hangisinin çalıştığı ve son hata klavyenin altında yazar.
 *  - Metin sadece kesinleşince yazılır (bazı uygulamalar geçici yazıyı bozuyor).
 */
public class VoiceIME extends InputMethodService {

    private static final int[] BLUE = {0xFF0A84FF, 0xFF5E5CE6};
    private static final int[] RED = {0xFFFF453A, 0xFFFF9F0A};

    private static final ComponentName GOOGLE_APP = new ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService");
    private static final ComponentName GOOGLE_TTS = new ComponentName(
            "com.google.android.tts",
            "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService");

    private final Handler h = new Handler(Looper.getMainLooper());

    // durum
    private SpeechRecognizer recognizer;
    private boolean wanted = false;      // sürekli dinleme isteniyor
    private boolean listening = false;   // tanıyıcı şu an çalışıyor
    private boolean password = false;
    private boolean busyRetried = false;
    private int session = 0;             // eski tanıyıcıdan gelen gecikmiş olayları yok saymak için

    // tanıyıcı adayları
    private final List<ComponentName> cands = new ArrayList<ComponentName>();
    private int candIdx = 0;
    private String lastErr = "-";

    // görünüm
    private TextView status;
    private TextView partial;
    private TextView diag;
    private FrameLayout micBtn;
    private ObjectAnimator pulse;

    private final Runnable autoStart = new Runnable() {
        @Override public void run() {
            if (!listening && !wanted && !password) begin();
        }
    };

    // ============================================================ görünüm

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    // ---------------------------------------------------------- klavye düzeni
    private LinearLayout keysBox;
    private FrameLayout bar;
    private LinearLayout suggRow;
    private static final int SN = 10;
    private final TextView[] sugg = new TextView[SN];
    private final java.util.LinkedList<String> hist = new java.util.LinkedList<String>();
    private volatile java.util.HashMap<String, String> lmAssoc = new java.util.HashMap<String, String>();
    private volatile double wAssoc = 0.008;
    private int page = 0;      // 0 harf, 1 sembol
    private int shift = 0;     // 0 kapalı, 1 tek harf, 2 kilitli
    private long lastShiftTap = 0;
    private TextView shiftKey;
    private final List<TextView> letterViews = new ArrayList<TextView>();
    private final List<String> letterBase = new ArrayList<String>();

    private static final int KEY_BG = 0xFF2C2C2E;
    private static final int KEY_FUNC = 0xFF3A3A3C;
    private static final int KEY_DOWN = 0xFF636366;

    private static final String[][] LET = {
        {"q","w","e","r","t","y","u","ı","o","p","ğ","ü"},
        {"a","s","d","f","g","h","j","k","l","ş","i"},
        {"z","x","c","v","b","n","m","ö","ç"}};
    private static final String[][] SYM = {
        {"1","2","3","4","5","6","7","8","9","0"},
        {"-","/",":",";","(",")","₺","$","€","£","="},
        {".",",","?","!","'","\"","%","+","&","@"}};

    // --- öneri sistemi: yerleşik kelimeler + senin yazdıkların
    private static final String BUILTIN =
        "ve bir bu da de için ile ama çok daha en gibi var yok evet hayır tamam merhaba selam teşekkürler "
      + "teşekkür ederim sağ ol rica ederim lütfen günaydın iyi akşamlar geceler günler nasılsın nasıl "
      + "neredesin ne zaman şimdi sonra yarın bugün dün bekliyorum geliyorum geldim gidiyorum gittim "
      + "yapıyorum yaptım yapacağım olur olmaz tabii tabi peki belki çünkü fakat ancak veya hem hiç her "
      + "herkes kimse biraz bence sence bana sana ona bize size onlara benim senin onun bizim sizin "
      + "onların burada orada şurada nerede nereye nereden kim kimin hangi kaç kadar sadece zaten artık "
      + "hala henüz bile mi mı mu mü değil değildi oldu olacak olarak olan olduğu için işte aslında "
      + "gerçekten mesela örneğin hemen şimdilik görüşürüz görüşmek üzere haber ver verir misin söyle "
      + "söyler misin bakar mısın bakalım bakarım yazıyorum yazdım arıyorum aradım aramak ödeme ödedim "
      + "ödeyeceğim hesap numarası numara telefon mesaj fotoğraf video ürün fiyat adet tane lira para "
      + "kasa personel müşteri sipariş teslim kargo gönderdim gönderiyorum gönderdi aldım aldık alacağım "
      + "satış alış stok mal toptan perakende fatura makbuz tutar toplam borç alacak vade tarih saat "
      + "gün hafta ay yıl sabah öğle akşam gece pazartesi salı çarşamba perşembe cuma cumartesi pazar "
      + "ocak şubat mart nisan mayıs haziran temmuz ağustos eylül ekim kasım aralık anne baba abi abla "
      + "kardeş eşim çocuk çocuklar aile arkadaş hocam abim ablam kardeşim efendim buyurun anladım "
      + "anlamadım olabilir mümkün imkansız önemli acil hızlı yavaş güzel kötü iyi büyük küçük yeni eski "
      + "doğru yanlış sorun problem çözüm yardım lazım gerek istiyorum istemiyorum biliyorum bilmiyorum "
      + "düşünüyorum sanırım galiba herhalde muhtemelen kesinlikle mutlaka aynen katılıyorum tamamdır "
      + "olsun neyse hadi haydi dikkat acaba sizce bence nedir neden niye nasılsınız iyiyim iyiyiz";

    // ---- statik dil modeli (derleme sırasında büyük Türkçe metinden üretilir)
    private volatile java.util.HashMap<String, Integer> words = new java.util.HashMap<String, Integer>();
    private volatile java.util.HashMap<String, String> lmBi = new java.util.HashMap<String, String>();
    private volatile java.util.HashMap<String, String> lmTri = new java.util.HashMap<String, String>();
    private volatile String[] vocab = new String[0];     // sıralı, önek araması için
    private double uniLog = 1;
    private volatile boolean lmReady = false;
    private volatile String lmStatus = "";

    private volatile java.util.HashMap<String, String[]> topPrefix = new java.util.HashMap<String, String[]>();
    private volatile double pen = 0.5;
    private boolean noFix = false;
    private volatile double diaPen = 0.03, realM = 0.8, retroM = 0.8;
    // ağır yazım hatası (çok harf yanlış) için bulanık eşleştirme
    private volatile double fzk = 3.5, fzm = 5.0, fzc = 12.0;
    private volatile int fzlim = 60;
    private volatile String[] freqWords = new String[0];
    private final int[] dA = new int[48], dB = new int[48], dC = new int[48];
    private int autoDel = 0;
    private boolean afterApos = false;
    private String autoRestore = null, autoLearn = null;
    private String prev3 = "";
    private final java.util.LinkedList<String> recent = new java.util.LinkedList<String>();

    // Türkçe Q klavyede yan yana tuşlar (yanlış basış düzeltme için)
    private static final java.util.HashMap<Character, String> NB = new java.util.HashMap<Character, String>();
    private static final String LETTERS = "abc\u00e7defg\u011fh\u0131ijklmno\u00f6prs\u015ftu\u00fcvyz";
    static {
        String[] rows = {"qwert y u\u0131op\u011f\u00fc".replace(" ", ""), "asdfghjkl\u015fi", "zxcvbnm\u00f6\u00e7"};
        java.util.HashMap<Character, java.util.HashSet<Character>> m =
                new java.util.HashMap<Character, java.util.HashSet<Character>>();
        for (String r : rows) {
            for (int i = 0; i + 1 < r.length(); i++) link(m, r.charAt(i), r.charAt(i + 1));
        }
        for (int k = 0; k < 2; k++) {
            String u = rows[k], l = rows[k + 1];
            for (int j = 0; j < l.length(); j++)
                for (int jj = j; jj <= j + 1; jj++) if (jj < u.length()) link(m, l.charAt(j), u.charAt(jj));
        }
        for (java.util.Map.Entry<Character, java.util.HashSet<Character>> e : m.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (char c : e.getValue()) sb.append(c);
            NB.put(e.getKey(), sb.toString());
        }
    }
    private static void link(java.util.HashMap<Character, java.util.HashSet<Character>> m, char a, char b) {
        if (!m.containsKey(a)) m.put(a, new java.util.HashSet<Character>());
        if (!m.containsKey(b)) m.put(b, new java.util.HashSet<Character>());
        m.get(a).add(b);
        m.get(b).add(a);
    }

    // ---- kişisel öğrenme (senin yazdıkların)
    private final java.util.HashMap<String, Integer> own = new java.util.HashMap<String, Integer>();
    private final java.util.HashMap<String, Integer> pairs = new java.util.HashMap<String, Integer>();   // "a>b"
    private final java.util.HashMap<String, Integer> triples = new java.util.HashMap<String, Integer>(); // "a b>c"
    private final java.util.HashMap<String, Integer> ctxTot = new java.util.HashMap<String, Integer>();  // bağlam toplamları
    private int ownMax = 1;

    private final StringBuilder cur = new StringBuilder();
    private String prev = "";
    private String prev2 = "";
    private int learnCount = 0;
    private long lastOwn = 0;
    private final Locale TR = new Locale("tr", "TR");

    private boolean isWord(String w) {
        if (w == null || w.length() < 2 || w.length() > 30) return false;
        for (int i = 0; i < w.length(); i++) if (!Character.isLetter(w.charAt(i))) return false;
        return true;
    }

    private void loadPersonal() {
        own.clear(); pairs.clear(); triples.clear(); ctxTot.clear();
        SharedPreferences sp = getSharedPreferences("sesli", MODE_PRIVATE);
        for (String e : sp.getString("own", "").split(",")) {
            int i = e.lastIndexOf(':');
            if (i <= 0) continue;
            try {
                int c = Integer.parseInt(e.substring(i + 1));
                own.put(e.substring(0, i), c);
                if (c > ownMax) ownMax = c;
            } catch (Exception ignored) { }
        }
        for (String e : sp.getString("pairs", "").split(",")) {
            int i = e.lastIndexOf(':');
            if (i <= 0) continue;
            try {
                String k = e.substring(0, i);
                int c = Integer.parseInt(e.substring(i + 1));
                pairs.put(k, c);
                addTot(k.substring(0, Math.max(0, k.indexOf('>'))), c);
            } catch (Exception ignored) { }
        }
        for (String e : sp.getString("triples", "").split(",")) {
            int i = e.lastIndexOf(':');
            if (i <= 0) continue;
            try {
                String k = e.substring(0, i);
                int c = Integer.parseInt(e.substring(i + 1));
                triples.put(k, c);
                addTot(k.substring(0, Math.max(0, k.indexOf('>'))), c);
            } catch (Exception ignored) { }
        }
        corrN.clear();
        for (String e : sp.getString("corr", "").split(",")) {
            int i = e.lastIndexOf(':');
            if (i <= 0) continue;
            try { corrN.put(e.substring(0, i), Integer.parseInt(e.substring(i + 1))); }
            catch (Exception ignored) { }
        }
        rebuildCorrIndex();
    }


    // --- kişisel düzeltme hafızası: sık yaptığın yazım hatası -> seçtiğin doğru kelime
    private final java.util.HashMap<String, Integer> corrN = new java.util.HashMap<String, Integer>();   // "yazılan>kelime"
    private final java.util.HashMap<String, String> corrWord = new java.util.HashMap<String, String>();  // yazılan -> en sık kelime
    private String autoFixWord = null;

    private void rebuildCorrIndex() {
        corrWord.clear();
        java.util.HashMap<String, Integer> top = new java.util.HashMap<String, Integer>();
        for (java.util.Map.Entry<String, Integer> e : corrN.entrySet()) {
            int i = e.getKey().indexOf('>');
            if (i <= 0) continue;
            String typed = e.getKey().substring(0, i);
            Integer t = top.get(typed);
            if (t == null || e.getValue() > t) {
                top.put(typed, e.getValue());
                corrWord.put(typed, e.getKey().substring(i + 1));
            }
        }
    }

    private void recordCorr(String typed, String word) {
        if (typed == null || word == null) return;
        typed = typed.toLowerCase(TR);
        word = word.toLowerCase(TR);
        if (typed.length() < 3 || typed.equals(word) || word.startsWith(typed)) return;
        if (!isWord(typed) || !isWord(word)) return;
        String k = typed + ">" + word;
        Integer c = corrN.get(k);
        corrN.put(k, (c == null ? 0 : c) + 1);
        if (corrN.size() > 4000) trim(corrN, 4000, 3000);
        rebuildCorrIndex();
    }

    private void forgetCorr(String typed, String word) {
        if (typed == null || word == null) return;
        corrN.remove(typed.toLowerCase(TR) + ">" + word.toLowerCase(TR));
        rebuildCorrIndex();
    }

    private void addTot(String ctx, int c) {
        if (ctx.length() == 0) return;
        Integer t = ctxTot.get(ctx);
        ctxTot.put(ctx, (t == null ? 0 : t) + c);
    }

    /** Büyük modeli arka planda yükler (klavye hemen açılır). */
    private void loadLmAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.util.HashMap<String, Integer> w = new java.util.HashMap<String, Integer>();
                    for (String x : BUILTIN.split(" ")) if (x.length() > 0) w.put(x, 50);
                    try {
                        java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(getAssets().open("words_tr.txt"), "UTF-8"), 1 << 16);
                        String line;
                        int rank = 0;
                        while ((line = r.readLine()) != null) {
                            int t = line.indexOf('\t');
                            String word = t > 0 ? line.substring(0, t) : line.trim();
                            if (!isWord(word) && !word.equals("o")) continue;
                            int cnt;
                            if (t > 0) {
                                try { cnt = Integer.parseInt(line.substring(t + 1).trim()); }
                                catch (Exception e) { cnt = 1; }
                            } else {
                                rank++;
                                cnt = Math.max(1, 20000 - rank);
                            }
                            Integer old = w.get(word);
                            if (old == null || old < cnt) w.put(word, cnt);
                        }
                        r.close();
                    } catch (Exception ignored) { }
                    java.util.HashMap<String, String> bi = readLm("lm_bi.txt");
                    java.util.HashMap<String, String> tri = readLm("lm_tri.txt");
                    java.util.HashMap<String, String> asc = readLm("lm_assoc.txt");
                    try {
                        java.io.BufferedReader pr = new java.io.BufferedReader(
                                new java.io.InputStreamReader(getAssets().open("params.txt"), "UTF-8"));
                        String pl;
                        while ((pl = pr.readLine()) != null) {
                            if (pl.startsWith("pen=")) pen = Double.parseDouble(pl.substring(4).trim());
                            if (pl.startsWith("dia=")) diaPen = Double.parseDouble(pl.substring(4).trim());
                            if (pl.startsWith("real=")) realM = Double.parseDouble(pl.substring(5).trim());
                            if (pl.startsWith("retro=")) retroM = Double.parseDouble(pl.substring(6).trim());
                            if (pl.startsWith("wassoc=")) wAssoc = Double.parseDouble(pl.substring(7).trim());
                            if (pl.startsWith("tsig=")) tSig = Double.parseDouble(pl.substring(5).trim());
                            if (pl.startsWith("twp=")) tWp = Double.parseDouble(pl.substring(4).trim());
                            if (pl.startsWith("tkc=")) tKc = Double.parseDouble(pl.substring(4).trim());
                            if (pl.startsWith("tmu=")) tMu = Double.parseDouble(pl.substring(4).trim());
                            if (pl.startsWith("tmk=")) tMk = Double.parseDouble(pl.substring(4).trim());
                            if (pl.startsWith("fzk=")) fzk = Double.parseDouble(pl.substring(4).trim());
                            if (pl.startsWith("fzlim=")) fzlim = Integer.parseInt(pl.substring(6).trim());
                            if (pl.startsWith("fzm=")) fzm = Double.parseDouble(pl.substring(4).trim());
                            if (pl.startsWith("fzc=")) fzc = Double.parseDouble(pl.substring(4).trim());
                        }
                        pr.close();
                    } catch (Exception ignored) { }
                    final java.util.HashMap<String, Integer> wf = w;
                    String[] byCount = w.keySet().toArray(new String[0]);
                    java.util.Arrays.sort(byCount, new java.util.Comparator<String>() {
                        @Override public int compare(String a, String b) { return wf.get(b) - wf.get(a); }
                    });
                    java.util.HashMap<String, java.util.ArrayList<String>> tp =
                            new java.util.HashMap<String, java.util.ArrayList<String>>();
                    for (String x : byCount) {
                        for (int n = 1; n <= 4; n++) {
                            if (x.length() < n) continue;
                            String key = x.substring(0, n);
                            java.util.ArrayList<String> l = tp.get(key);
                            if (l == null) { l = new java.util.ArrayList<String>(); tp.put(key, l); }
                            if (l.size() < (n <= 2 ? 120 : 60)) l.add(x);
                        }
                    }
                    java.util.HashMap<String, String[]> tpa = new java.util.HashMap<String, String[]>();
                    for (java.util.Map.Entry<String, java.util.ArrayList<String>> e : tp.entrySet())
                        tpa.put(e.getKey(), e.getValue().toArray(new String[0]));
                    topPrefix = tpa;
                    java.util.ArrayList<String> fl = new java.util.ArrayList<String>();
                    for (String x : byCount) {
                        if (x.length() >= 3 && fl.size() < 120000) fl.add(x);
                    }
                    freqWords = fl.toArray(new String[0]);
                    String[] v = w.keySet().toArray(new String[0]);
                    java.util.Arrays.sort(v);
                    int mx = 1;
                    for (Integer c : w.values()) if (c > mx) mx = c;
                    uniLog = Math.log(1 + mx);
                    words = w;
                    lmBi = bi;
                    lmTri = tri;
                    lmAssoc = asc;
                    vocab = v;
                    lmReady = true;
                    lmStatus = "";
                    h.post(new Runnable() { @Override public void run() { purgeJunk(); updateSuggestions(); } });
                } catch (Throwable t) {
                    lmStatus = "Model hatası: " + t.getClass().getSimpleName();
                    h.post(new Runnable() { @Override public void run() { updateSuggestions(); } });
                }
            }
        }).start();
    }

    /** Önceki sürümlerde yanlış yazılıp öğrenilmiş, sözlükte olmayan ve az geçen kelimeleri siler. */
    private void purgeJunk() {
        java.util.Iterator<java.util.Map.Entry<String, Integer>> it = own.entrySet().iterator();
        boolean changed = false;
        while (it.hasNext()) {
            java.util.Map.Entry<String, Integer> e = it.next();
            Integer b = words.get(e.getKey());
            if ((b == null || b == 0) && e.getValue() < 3) { it.remove(); changed = true; }
        }
        if (!changed) return;
        for (java.util.Iterator<String> pi = pairs.keySet().iterator(); pi.hasNext();) {
            String k = pi.next();
            int g = k.indexOf('>');
            if (!okWord(k.substring(0, g)) || !okWord(k.substring(g + 1))) pi.remove();
        }
        for (java.util.Iterator<String> ti = triples.keySet().iterator(); ti.hasNext();) {
            String k = ti.next();
            int g = k.indexOf('>');
            String[] a = k.substring(0, g).split(" ");
            boolean ok = okWord(k.substring(g + 1));
            for (String x : a) ok = ok && okWord(x);
            if (!ok) ti.remove();
        }
        ctxTot.clear();
        for (java.util.Map.Entry<String, Integer> e : pairs.entrySet()) {
            String k = e.getKey();
            addTot(k.substring(0, k.indexOf('>')), e.getValue());
        }
        for (java.util.Map.Entry<String, Integer> e : triples.entrySet()) {
            String k = e.getKey();
            addTot(k.substring(0, k.indexOf('>')), e.getValue());
        }
        saveWords();
    }

    private boolean okWord(String w) {
        if (w.equals("^")) return true;
        Integer b = words.get(w);
        return (b != null && b > 0) || own.containsKey(w);
    }

    private java.util.HashMap<String, String> readLm(String name) {
        java.util.HashMap<String, String> m = new java.util.HashMap<String, String>();
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(getAssets().open(name), "UTF-8"), 1 << 16);
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t > 0) m.put(line.substring(0, t), line.substring(t + 1));
            }
            r.close();
        } catch (Exception ignored) { }
        return m;
    }

    /** Bir kelimeyi öğrenir; önceki kelimelerle bağlantısını da kaydeder. */
    private final java.util.HashSet<String> pending = new java.util.HashSet<String>();

    private void learn(String w) {
        if (w == null) return;
        w = w.toLowerCase(TR);
        if (!isWord(w)) return;
        if (lmReady && !isKnown(w)) {
            // bilmediğim kelime: hemen kelime hafızasına al (bağlam kalıplarına ikinci kez yazılınca girer,
            // böylece yazım hataları cümle bağlamını bozmaz)
            own.put(w, 1);
            if (ownMax < 1) ownMax = 1;
            saveSoon();
            return;
        }
        Integer c = own.get(w);
        int nc = (c == null ? 0 : c) + 1;
        own.put(w, nc);
        if (nc > ownMax) ownMax = nc;
        if (prev.length() > 0) {
            String k = prev + ">" + w;
            Integer pc = pairs.get(k);
            pairs.put(k, (pc == null ? 0 : pc) + 1);
            addTot(prev, 1);
            String c2 = prev2.length() > 0 ? prev2 : "^";
            String k3 = c2 + " " + prev + ">" + w;
            Integer tc = triples.get(k3);
            triples.put(k3, (tc == null ? 0 : tc) + 1);
            addTot(c2 + " " + prev, 1);
        } else {
            // cümle başı
            String k = "^>" + w;
            Integer pc = pairs.get(k);
            pairs.put(k, (pc == null ? 0 : pc) + 1);
            addTot("^", 1);
        }
        recent.remove(w);
        recent.add(w);
        while (recent.size() > 40) recent.removeFirst();
        if (prev.length() == 0) hist.clear();
        hist.addFirst(w);
        while (hist.size() > 8) hist.removeLast();
        prev3 = prev2;
        prev2 = prev;
        prev = w;
        if (++learnCount % 6 == 0) saveWords();
    }

    /** Konuşarak yazılan cümleyi de öğrenir. */
    private void learnText(String text) {
        for (String t : text.split("\\s+")) {
            String w = t.replaceAll("[^\\p{L}]", "");
            if (w.length() > 0) learn(w);
        }
    }

    private void trim(java.util.HashMap<String, Integer> m, int max, int target) {
        if (m.size() <= max) return;
        java.util.Iterator<java.util.Map.Entry<String, Integer>> it = m.entrySet().iterator();
        while (it.hasNext() && m.size() > target) if (it.next().getValue() <= 1) it.remove();
    }

    private String dump(java.util.HashMap<String, Integer> m) {
        StringBuilder a = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : m.entrySet()) {
            if (a.length() > 0) a.append(',');
            a.append(e.getKey()).append(':').append(e.getValue());
        }
        return a.toString();
    }

    private long lastImport = 0;

    /** Başka ekranda (Importer) yeni şey öğrenildiyse bellektekini ona göre yenile. */
    private int delVer = 0;

    private boolean importChanged() {
        long t = getSharedPreferences("sesli", MODE_PRIVATE).getLong("imported_at", 0);
        if (t != lastImport) {
            lastImport = t;
            return true;
        }
        return false;
    }

    /** Öğrenilmiş bir kelimeyi (ve onunla ilgili tüm kalıpları, düzeltme anılarını) siler. */
    private boolean forgetWord(String w) {
        boolean hit = own.remove(w) != null;
        for (java.util.Iterator<String> it = pairs.keySet().iterator(); it.hasNext();) {
            String k = it.next();
            int g = k.indexOf('>');
            if (k.substring(0, g).equals(w) || k.substring(g + 1).equals(w)) { it.remove(); hit = true; }
        }
        for (java.util.Iterator<String> it = triples.keySet().iterator(); it.hasNext();) {
            String k = it.next();
            int g = k.indexOf('>');
            boolean m = k.substring(g + 1).equals(w);
            for (String x : k.substring(0, g).split(" ")) if (x.equals(w)) m = true;
            if (m) { it.remove(); hit = true; }
        }
        for (java.util.Iterator<java.util.Map.Entry<String, String>> it = corrWord.entrySet().iterator(); it.hasNext();) {
            java.util.Map.Entry<String, String> e = it.next();
            if (e.getKey().equals(w) || e.getValue().equals(w)) { it.remove(); hit = true; }
        }
        for (java.util.Iterator<String> it = corrN.keySet().iterator(); it.hasNext();) {
            String k = it.next();
            int g = k.indexOf('>');
            if (k.substring(0, g).equals(w) || k.substring(g + 1).equals(w)) it.remove();
        }
        recent.remove(w);
        hist.remove(w);
        pending.remove(w);
        ctxTot.clear();
        for (java.util.Map.Entry<String, Integer> e : pairs.entrySet()) {
            String k = e.getKey();
            addTot(k.substring(0, k.indexOf('>')), e.getValue());
        }
        for (java.util.Map.Entry<String, Integer> e : triples.entrySet()) {
            String k = e.getKey();
            addTot(k.substring(0, k.indexOf('>')), e.getValue());
        }
        if (hit) saveWords();
        return hit;
    }

    private void saveSoon() {
        if (++learnCount % 3 == 0) saveWords();
    }

    private void saveWords() {
        if (importChanged()) {
            loadPersonal();
            return;
        }
        trim(own, 8000, 6000);
        trim(pairs, 12000, 9000);
        trim(triples, 15000, 11000);
        final java.util.HashMap<String, Integer> o2 = new java.util.HashMap<String, Integer>(own);
        final java.util.HashMap<String, Integer> p2 = new java.util.HashMap<String, Integer>(pairs);
        final java.util.HashMap<String, Integer> t2 = new java.util.HashMap<String, Integer>(triples);
        final java.util.HashMap<String, Integer> c2 = new java.util.HashMap<String, Integer>(corrN);
        new Thread(new Runnable() {
            @Override public void run() {
                getSharedPreferences("sesli", MODE_PRIVATE).edit()
                        .putString("own", dump(o2)).putString("pairs", dump(p2))
                        .putString("triples", dump(t2)).putString("corr", dump(c2)).apply();
            }
        }).start();
    }

    /** İmleçten önceki yazıdan o anki kelimeyi ve önceki iki kelimeyi okur. */
    private boolean sliding = false;

    private void syncFromText() {
        InputConnection ic = getCurrentInputConnection();
        cur.setLength(0);
        prev = "";
        prev2 = "";
        prev3 = "";
        if (ic == null) return;
        if (!sliding) {
            // imleç bir kelimenin ortasındaysa: kelimenin tamamı seçilmiş say, imleci kelime sonuna al
            CharSequence ch = ic.getTextBeforeCursor(1, 0);
            CharSequence ta = ic.getTextAfterCursor(40, 0);
            if (ch != null && ch.length() == 1 && Character.isLetter(ch.charAt(0)) && ta != null) {
                String t = ta.toString();
                int tl = 0;
                while (tl < t.length() && Character.isLetter(t.charAt(tl))) tl++;
                if (tl > 0 && tl < 40) {
                    markOwn();
                    String tail = t.substring(0, tl);
                    ic.beginBatchEdit();
                    ic.deleteSurroundingText(0, tl);
                    ic.commitText(tail, 1);
                    ic.endBatchEdit();
                }
            }
        }
        CharSequence cs = ic.getTextBeforeCursor(80, 0);
        if (cs == null) return;
        String b = cs.toString();
        int pos = b.length();
        int i = pos;
        while (i > 0 && Character.isLetter(b.charAt(i - 1))) i--;
        cur.append(b.substring(i, pos));
        afterApos = cur.length() > 0 && i > 0 && (b.charAt(i - 1) == '\'' || b.charAt(i - 1) == '\u2019');
        pos = i;
        // önceki iki kelime (araya sadece boşluk girebilir; nokta/soru cümleyi bitirir)
        String[] got = new String[2];
        for (int n = 0; n < 2; n++) {
            int j = pos;
            while (j > 0 && (b.charAt(j - 1) == ' ' || b.charAt(j - 1) == ' ' || b.charAt(j - 1) == ',')) j--;
            if (j == pos && !(n == 0 && cur.length() == 0)) break;   // boşluk yoksa kelime bitişik
            int k = j;
            while (k > 0 && Character.isLetter(b.charAt(k - 1))) k--;
            if (k == j) break;
            got[n] = b.substring(k, j).toLowerCase(TR);
            pos = k;
        }
        if (got[0] != null) prev = got[0];
        if (got[1] != null) prev2 = got[1];
        // aynı cümledeki önceki kelimeler (anlam ilişkisi için)
        hist.clear();
        int q = pos;
        while (hist.size() < 8 && q > 0) {
            while (q > 0 && (b.charAt(q - 1) == ' ' || b.charAt(q - 1) == ',')) q--;
            int k = q;
            while (k > 0 && Character.isLetter(b.charAt(k - 1))) k--;
            if (k == q) break;
            hist.addLast(b.substring(k, q).toLowerCase(TR));
            q = k;
        }
        if (prev2.length() > 0 && !hist.contains(prev2)) hist.addFirst(prev2);
        if (prev.length() > 0 && !hist.contains(prev)) hist.addFirst(prev);
    }

    private void markOwn() {
        lastOwn = android.os.SystemClock.uptimeMillis();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candStart, int candEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candStart, candEnd);
        if (android.os.SystemClock.uptimeMillis() - lastOwn < 400) return;
        if (newSelStart == oldSelStart && newSelEnd == oldSelEnd) return;
        syncFromText();
        updateSuggestions();
        autoShift();
    }

    // ---------------------------------------------------------- tahmin

    private static void addSucc(String line, double weight, java.util.HashMap<String, Double> acc) {
        if (line == null) return;
        String[] parts = line.split(",");
        double total = 0;
        int[] cnt = new int[parts.length];
        String[] w = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int c = parts[i].lastIndexOf(':');
            if (c <= 0) continue;
            w[i] = parts[i].substring(0, c);
            try { cnt[i] = Integer.parseInt(parts[i].substring(c + 1)); } catch (Exception e) { cnt[i] = 0; }
            total += cnt[i];
        }
        if (total <= 0) return;
        for (int i = 0; i < parts.length; i++) {
            if (w[i] == null) continue;
            Double o = acc.get(w[i]);
            acc.put(w[i], (o == null ? 0 : o) + weight * cnt[i] / total);
        }
    }

    private int idxStamp = -1;
    private final java.util.HashMap<String, java.util.ArrayList<String>> idxPairs =
            new java.util.HashMap<String, java.util.ArrayList<String>>();
    private final java.util.HashMap<String, java.util.ArrayList<String>> idxTriples =
            new java.util.HashMap<String, java.util.ArrayList<String>>();

    private void ensureIdx() {
        int stamp = pairs.size() * 100003 + triples.size();
        if (stamp == idxStamp) return;
        idxStamp = stamp;
        buildIdx(pairs, idxPairs);
        buildIdx(triples, idxTriples);
    }

    private static void buildIdx(java.util.HashMap<String, Integer> src,
                                 java.util.HashMap<String, java.util.ArrayList<String>> idx) {
        idx.clear();
        for (String k : src.keySet()) {
            int g = k.indexOf('>');
            if (g <= 0) continue;
            String c = k.substring(0, g);
            java.util.ArrayList<String> l = idx.get(c);
            if (l == null) { l = new java.util.ArrayList<String>(4); idx.put(c, l); }
            l.add(k);
        }
    }

    private void addPersonal(String prefix, double weight, java.util.HashMap<String, Double> acc,
                             java.util.HashMap<String, Integer> src, String ctx) {
        Integer tot = ctxTot.get(ctx);
        if (tot == null || tot == 0) return;
        ensureIdx();
        java.util.ArrayList<String> ks = (src == pairs ? idxPairs : idxTriples).get(ctx);
        if (ks == null) return;
        int off = ctx.length() + 1;
        double conf = Math.min(1.0, tot / 3.0);
        for (String k : ks) {
            Integer v = src.get(k);
            if (v == null) continue;
            String w = k.substring(off);
            Double o = acc.get(w);
            acc.put(w, (o == null ? 0 : o) + weight * conf * v / tot);
        }
    }


    /** Cümledeki önceki kelimelerle anlamca ilişkili kelimelere ek puan verir. */
    private void addAssoc(java.util.HashMap<String, Double> acc) {
        if (wAssoc <= 0 || lmAssoc.isEmpty()) return;
        double decay = 1.0;
        int k = 0;
        for (String h : hist) {
            if (k++ >= 8) break;
            String line = lmAssoc.get(h);
            if (line != null) {
                for (String part : line.split(",")) {
                    int c = part.lastIndexOf(':');
                    if (c <= 0) continue;
                    double v;
                    try { v = Double.parseDouble(part.substring(c + 1)) / 10.0; } catch (Exception e) { continue; }
                    String w = part.substring(0, c);
                    Double o = acc.get(w);
                    acc.put(w, (o == null ? 0 : o) + wAssoc * v * decay);
                }
            }
            decay *= 0.85;
        }
    }


    // ---------------------------------------------------------- dokunma tabanlı çözümleyici
    // Gboard / SwiftKey ilkesi: her dokunuşun ekrandaki yeri "hangi tuşu kastetti" olasılığına çevrilir;
    // kelime adayları bu olasılıklar + kelime sıklığı + bağlam ile birlikte puanlanır.
    private static final String L32 = "qwertyuıopğüasdfghjklşizxcvbnmöç";
    private volatile double tSig = 0.55, tWp = 1.0, tKc = 12.0, tMu = 4.0, tMk = 8.0;
    private final java.util.ArrayList<float[]> taps = new java.util.ArrayList<float[]>();
    private final float[] kcx = new float[32], kcy = new float[32];
    private float refW = 1f;
    private boolean centersOk = false;
    private float tapX, tapY;
    private boolean tapFresh = false;
    // kişisel kalibrasyon: parmağın sistematik kayması (tuş genişliği biriminde), 3 bölge (sol/orta/sağ)
    private final float[] bX = new float[3], bY = new float[3];
    private final int[] bN = new int[3];
    private final java.util.ArrayList<float[]> tapPts = new java.util.ArrayList<float[]>();

    private void ensureCenters() {
        if (centersOk || letterViews.size() != 32) return;
        int[] loc = new int[2];
        for (int i = 0; i < 32; i++) {
            View v = letterViews.get(i);
            if (v.getWidth() == 0) return;
            v.getLocationOnScreen(loc);
            int idx = L32.indexOf(letterBase.get(i).charAt(0));
            if (idx < 0) continue;
            kcx[idx] = loc[0] + v.getWidth() / 2f;
            kcy[idx] = loc[1] + v.getHeight() / 2f;
        }
        refW = Math.max(1, letterViews.get(0).getWidth());
        centersOk = true;
    }

    private int zoneOf(int idx) {
        float left = kcx[0] - refW / 2f;
        int z = (int) (3f * (kcx[idx] - left) / (12f * refW));
        return z < 0 ? 0 : (z > 2 ? 2 : z);
    }

    /** Doğru bilinen bir kelimenin dokunuşlarından parmak kaymasını öğrenir. */
    private void learnBias(String truth) {
        ensureCenters();
        if (!centersOk || truth.length() < 3 || tapPts.size() != truth.length()) return;
        for (int i = 0; i < truth.length(); i++) {
            float[] pt = tapPts.get(i);
            int idx = L32.indexOf(truth.charAt(i));
            if (pt == null || idx < 0) return;
        }
        for (int i = 0; i < truth.length(); i++) {
            float[] pt = tapPts.get(i);
            int idx = L32.indexOf(truth.charAt(i));
            float dx = (pt[0] - kcx[idx]) / refW, dy = (pt[1] - kcy[idx]) / refW;
            if (Math.abs(dx) > 0.9f || Math.abs(dy) > 0.9f) continue;
            int z = zoneOf(idx);
            float a = Math.max(0.01f, 1f / (bN[z] + 1));
            bX[z] += (dx - bX[z]) * a;
            bY[z] += (dy - bY[z]) * a;
            bX[z] = Math.max(-0.35f, Math.min(0.35f, bX[z]));
            bY[z] = Math.max(-0.35f, Math.min(0.35f, bY[z]));
            if (bN[z] < 100000) bN[z]++;
        }
        if (++biasSaveCnt % 25 == 0) saveBias();
    }

    private int biasSaveCnt = 0;

    private void saveBias() {
        StringBuilder sb = new StringBuilder();
        for (int z = 0; z < 3; z++) sb.append(bX[z]).append(',').append(bY[z]).append(',').append(bN[z]).append(';');
        getSharedPreferences("sesli", MODE_PRIVATE).edit().putString("bias", sb.toString()).apply();
    }

    private void loadBias() {
        try {
            String[] zs = getSharedPreferences("sesli", MODE_PRIVATE).getString("bias", "").split(";");
            for (int z = 0; z < 3 && z < zs.length; z++) {
                String[] f = zs[z].split(",");
                if (f.length < 3) continue;
                bX[z] = Float.parseFloat(f[0]);
                bY[z] = Float.parseFloat(f[1]);
                bN[z] = Integer.parseInt(f[2]);
            }
        } catch (Throwable ignored) { }
    }

    private float[] tapVec(float x, float y) {
        ensureCenters();
        if (!centersOk) return null;
        float[] v = new float[32];
        double inv = 1.0 / (2 * tSig * tSig * refW * refW);
        for (int i = 0; i < 32; i++) {
            int z = zoneOf(i);
            float dx = x - kcx[i] - bX[z] * refW, dy = y - kcy[i] - bY[z] * refW;
            v[i] = (float) (-(dx * dx + dy * dy) * inv);
        }
        return v;
    }

    private float[] synth(char ch) {
        float[] v = new float[32];
        String nb = NB.get(ch);
        for (int i = 0; i < 32; i++) {
            char c = L32.charAt(i);
            v[i] = c == ch ? 0f : (nb != null && nb.indexOf(c) >= 0) ? -1.8f : -7f;
        }
        return v;
    }

    private float[][] tapsFor(String p, boolean strict) {
        int n = p.length();
        float[][] r = new float[n][];
        boolean ok = !strict && taps.size() == n;
        for (int i = 0; i < n; i++) {
            float[] v = ok ? taps.get(i) : null;
            if (strict) {
                v = new float[32];
                for (int j = 0; j < 32; j++) v[j] = L32.charAt(j) == p.charAt(i) ? 0f : -9f;
            } else if (v == null) {
                v = synth(p.charAt(i));
            }
            r[i] = v;
        }
        return r;
    }

    private boolean hasPrefix(String q) {
        String[] arr = vocab;
        int lo = 0, hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid].compareTo(q) < 0) lo = mid + 1; else hi = mid;
        }
        return lo < arr.length && arr[lo].startsWith(q);
    }

    private int[] topLetters(float[] v, int k) {
        int[] o = new int[k];
        boolean[] used = new boolean[32];
        for (int a = 0; a < k; a++) {
            int bi = 0;
            float bv = -1e9f;
            for (int i = 0; i < 32; i++) if (!used[i] && v[i] > bv) { bv = v[i]; bi = i; }
            used[bi] = true;
            o[a] = bi;
        }
        return o;
    }

    private double touchOf(String w, float[][] ll, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) {
            int idx = L32.indexOf(w.charAt(i));
            s += idx < 0 ? -8.0 : ll[i][idx];
        }
        return s;
    }

    /** Yazılan harflerden kaç tanesi, kelimedeki harfle uyuşmuyor (dokunma izine göre). */
    private int mism(String w, float[][] ll, int n) {
        int m = 0;
        int k = Math.min(n, w.length());
        for (int i = 0; i < k; i++) {
            int idx = L32.indexOf(w.charAt(i));
            if (idx < 0 || ll[i][idx] < -1.0f) m++;
        }
        return m;
    }

    private double priorOf(String w) {
        Integer c = words.get(w);
        double lc = c == null ? 0 : Math.log(1 + c);
        Integer o = own.get(w);
        if (o != null) lc = Math.max(lc, Math.log(1 + o * (o >= 2 ? 20000.0 : 3000.0)));
        return lc;
    }

    private double totalScore(String w, double touch, int n, java.util.HashMap<String, Double> ctx) {
        Double cx = ctx.get(w);
        double s = tWp * priorOf(w) + tKc * (cx == null ? 0 : cx) - 0.002 * w.length() + touch;
        if (w.length() > n) s -= 0.35 * (w.length() - n);
        if (recent.contains(w)) s += 0.3;
        return s;
    }

    private void addCand(java.util.HashMap<String, Object[]> best, String w, double touch, int n,
                         java.util.HashMap<String, Double> ctx) {
        double sc = totalScore(w, touch, n, ctx);
        Object[] o = best.get(w);
        if (o == null || sc > (Double) o[1]) best.put(w, new Object[]{w, sc, touch});
    }

    /** Yazılan harflerin dokunma izinden olası kelimeler: {kelime, puan, dokunma puanı}, puana göre sıralı. */
    private java.util.List<Object[]> decode(String p, java.util.HashMap<String, Double> ctx,
                                            boolean full, boolean strict) {
        int n = p.length();
        java.util.ArrayList<Object[]> res = new java.util.ArrayList<Object[]>();
        if (!lmReady || n < 1 || n > 24) return res;
        float[][] ll = tapsFor(p, strict);
        java.util.HashMap<String, Object[]> best = new java.util.HashMap<String, Object[]>();

        // 1) ızgara araması: her konumda en olası harfler, yalnızca sözlükte öneki olanlar
        final int W = 60;
        String[] bp = new String[]{""};
        double[] bs = new double[]{0};
        int bn = 1;
        for (int i = 0; i < n; i++) {
            int[] ord = topLetters(ll[i], 6);
            String[] np = new String[bn * 6];
            double[] ns = new double[bn * 6];
            int nn = 0;
            for (int b = 0; b < bn; b++) {
                for (int k = 0; k < 6; k++) {
                    int idx = ord[k];
                    double l = ll[i][idx];
                    if (l < -7.0) break;
                    String q = bp[b] + L32.charAt(idx);
                    if (!hasPrefix(q)) continue;
                    np[nn] = q;
                    ns[nn] = bs[b] + l;
                    nn++;
                }
            }
            if (nn == 0) { bn = 0; break; }
            final double[] nsf = ns;
            Integer[] ix = new Integer[nn];
            for (int j = 0; j < nn; j++) ix[j] = j;
            java.util.Arrays.sort(ix, new java.util.Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) { return Double.compare(nsf[b], nsf[a]); }
            });
            int keep = Math.min(W, nn);
            String[] tp = new String[keep];
            double[] ts = new double[keep];
            for (int j = 0; j < keep; j++) { tp[j] = np[ix[j]]; ts[j] = ns[ix[j]]; }
            bp = tp; bs = ts; bn = keep;
        }
        for (int b = 0; b < Math.min(bn, 20); b++) {
            String P = bp[b];
            if (P.length() != n) continue;
            int mmP = mism(P, ll, n);
            if (!full && mmP > (n + 2) / 4 + 0) continue;
            if (words.containsKey(P)) addCand(best, P, bs[b], n, ctx);
            if (!full) {
                for (String w : rangeWords(P, 60)) addCand(best, w, bs[b], n, ctx);
            }
        }

        // 2) senin kelimelerin ve bağlamın önerdikleri (dokunma olasılığıyla)
        for (String w : own.keySet()) {
            int lw = w.length();
            if (full ? lw != n : lw < n) continue;
            double t = touchOf(w, ll, n);
            if (t < -6.0 || mism(w, ll, n) > (n + 2) / 4) continue;
            addCand(best, w, t, n, ctx);
        }
        for (String w : ctx.keySet()) {
            int lw = w.length();
            if (full ? lw != n : lw < n) continue;
            if (!isKnown(w)) continue;
            double t = touchOf(w, ll, n);
            if (t < -6.0 || mism(w, ll, n) > (n + 2) / 4) continue;
            addCand(best, w, t, n, ctx);
        }

        // 2a) tam yazdığın kelime sözlükteyse öne geçsin
        if (!full && isKnown(p)) addCand(best, p, touchOf(p, ll, n) + 2.5, n, ctx);

        // 2b) daha önce bu yazımı düzelttiğin kelime
        String cw = corrWord.get(p);
        if (cw != null && !strict) {
            Integer cn = corrN.get(p + ">" + cw);
            addCand(best, cw, 2.0 + Math.min(cn == null ? 1 : cn, 5), cw.length(), ctx);
        }

        // 3) eksik/fazla harf, yer değiştirme ve Türkçe harfsiz yazım
        if (!strict && n >= 3 && n <= 12) {
            for (String[] vk : variants(p)) {
                String k = vk[1];
                if (k.equals("sub")) continue;
                if (!full && k.equals("ins")) continue;
                String v = vk[0];
                if (v.length() < 3) continue;
                double cost = k.equals("tr") ? 1.8 : k.equals("dia") ? 0.4 * Integer.parseInt(vk[2]) : 2.5;
                if (full) {
                    if (words.containsKey(v)) addCand(best, v, -cost, v.length(), ctx);
                } else {
                    for (String w : rangeWords(v, 12)) addCand(best, w, -cost, v.length(), ctx);
                }
            }
        }

        // 3b) iki hatalı kelime (örn. eksik harf + yanlış tuş): önce bir düzenleme, sonra bir komşu/yer değiştirme/silme
        if (full && !strict && n >= 5 && n <= 14) {
            for (String[] v1 : variants(p)) {
                String k1 = v1[1];
                if (k1.equals("sub")) continue;
                double c1 = k1.equals("tr") ? 1.8 : k1.equals("dia") ? 0.4 * Integer.parseInt(v1[2]) : 2.5;
                String a = v1[0];
                int m = a.length();
                if (m < 4) continue;
                for (int i = 0; i < m; i++) {
                    String nb = NB.get(a.charAt(i));
                    if (nb != null) {
                        for (int k = 0; k < nb.length(); k++) {
                            String v = a.substring(0, i) + nb.charAt(k) + a.substring(i + 1);
                            if (words.containsKey(v)) addCand(best, v, -(c1 + 2.4 + 2.5), v.length(), ctx);
                        }
                    }
                    if (m >= 5) {
                        String v = a.substring(0, i) + a.substring(i + 1);
                        if (words.containsKey(v)) addCand(best, v, -(c1 + 2.5 + 2.5), v.length(), ctx);
                    }
                    if (i + 1 < m && a.charAt(i) != a.charAt(i + 1)) {
                        String v = a.substring(0, i) + a.charAt(i + 1) + a.charAt(i) + a.substring(i + 2);
                        if (words.containsKey(v)) addCand(best, v, -(c1 + 1.8 + 2.5), v.length(), ctx);
                    }
                }
            }
        }

        res.addAll(best.values());
        java.util.Collections.sort(res, new java.util.Comparator<Object[]>() {
            @Override public int compare(Object[] a, Object[] b) {
                return Double.compare((Double) b[1], (Double) a[1]);
            }
        });
        while (res.size() > 14) res.remove(res.size() - 1);
        return res;
    }

    private double uniScore(String w) {
        Integer c = words.get(w);
        double s = c == null ? 0 : Math.log(1 + c) / uniLog;
        Integer o = own.get(w);
        if (o != null) s += 0.5 * Math.min(1.0, (double) o / Math.max(3, ownMax / 2));
        return s;
    }

    /** Cümle başında (boş kutu, nokta/soru/ünlem + boşluk, yeni satır) ilk harf otomatik büyük olsun. */
    private void autoCaps() {
        if (page != 0 || shift == 2 || password || noFix) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence b = ic.getTextBeforeCursor(3, 0);
        String t = b == null ? "" : b.toString();
        boolean start;
        if (t.isEmpty()) start = true;
        else {
            char l = t.charAt(t.length() - 1);
            String tr = t.trim();
            start = l == '\n' || ((l == ' ') && (tr.isEmpty() || ".?!".indexOf(tr.charAt(tr.length() - 1)) >= 0));
        }
        if (start && shift == 0) { shift = 1; refreshLabels(); }
    }

    private void updateSuggestions0() {
        if (cur.length() == 0) autoCaps();
        if (sugg[0] == null) return;
        if (afterApos) {
            for (int i = 0; i < SN; i++) sugg[i].setText("");
            return;
        }
        String[] kc = kctx();
        boolean noCtx = prev.length() > 0 && kc[1].length() == 0;
        String c1 = prev.length() == 0 ? "^" : kc[1];
        String c2 = prev.length() == 0 ? "" : (kc[0].length() > 0 ? kc[0] : "^");

        // bağlama göre aday puanları
        java.util.HashMap<String, Double> ctx = new java.util.HashMap<String, Double>();
        if (!noCtx) {
            if (c2.length() > 0) addSucc(lmTri.get(c2 + " " + c1), 1.0, ctx);
            addSucc(lmBi.get(c1), 0.45, ctx);
            if (c2.length() > 0) addPersonal("", 5.0, ctx, triples, c2 + " " + c1);
            addPersonal("", 3.0, ctx, pairs, c1);
        }

        String[] out = new String[SN];
        java.util.Arrays.fill(out, "");
        double[] sc = new double[SN];
        java.util.Arrays.fill(sc, -1);

        if (cur.length() == 0) {
            // ilk 3: yalnız cümle kalıbı (n-gram); 4-10: anlamca ilişkili kelimeler de katılır
            String[] o3 = new String[3];
            double[] s3 = new double[3];
            java.util.Arrays.fill(o3, "");
            java.util.Arrays.fill(s3, -1);
            for (java.util.Map.Entry<String, Double> e : ctx.entrySet())
                rank(o3, s3, e.getKey(), e.getValue());
            java.util.HashMap<String, Double> ctx2 = new java.util.HashMap<String, Double>(ctx);
            addAssoc(ctx2);
            for (java.util.Map.Entry<String, Double> e : ctx2.entrySet())
                rank(out, sc, e.getKey(), e.getValue());
            int pos = 0;
            String[] merged = new String[SN];
            java.util.Arrays.fill(merged, "");
            for (String x : o3) if (x.length() > 0) merged[pos++] = x;
            for (String x : out) {
                if (pos >= SN) break;
                if (x.length() == 0) continue;
                boolean dup = false;
                for (int i = 0; i < pos; i++) if (merged[i].equals(x)) { dup = true; break; }
                if (!dup) merged[pos++] = x;
            }
            for (int i = 0; i < SN; i++) out[i] = merged[i];
            // boşlukları sık kullandığın kelimelerle doldur
            if (out[5].length() == 0) {
                for (java.util.Map.Entry<String, Integer> e : own.entrySet())
                    rank(out, sc, e.getKey(), 0.01 * e.getValue() / Math.max(1, ownMax));
            }
            if (out[5].length() == 0) {
                String[] def = new String[]{"ve", "bir", "bu", "evet", "tamam", "merhaba"};
                for (String d : def) {
                    if (!words.containsKey(d) && lmReady) continue;
                    rank(out, sc, d, 0.0001);
                }
            }
            if (shift == 1) {
                for (int i = 0; i < SN; i++)
                    if (out[i].length() > 0) out[i] = out[i].substring(0, 1).toUpperCase(TR) + out[i].substring(1);
            }
        } else {
            String p = cur.toString().toLowerCase(TR);
            java.util.Arrays.fill(sc, -1e18);
            for (Object[] r : decode(p, ctx, false, noFix)) rank(out, sc, (String) r[0], (Double) r[1]);
            boolean capFirst = Character.isUpperCase(cur.charAt(0));
            if (capFirst) {
                for (int i = 0; i < SN; i++) {
                    if (out[i].length() > 0) out[i] = out[i].substring(0, 1).toUpperCase(TR) + out[i].substring(1);
                }
            }
            // yazdığın hali de üçüncü sırada dursun (isimler, argo vb. için)
            String lit = cur.toString();
            boolean has = false;
            for (int i = 0; i < SN; i++) if (out[i].equalsIgnoreCase(lit)) { has = true; break; }
            int hasAt = -1;
            for (int i = 0; i < SN; i++) if (out[i].equalsIgnoreCase(lit)) { hasAt = i; break; }
            if (lit.length() >= 2 && (hasAt < 0 || hasAt > 2)) {
                int at = 2;
                int from = hasAt < 0 ? SN - 1 : hasAt;
                for (int i = from; i > at; i--) out[i] = out[i - 1];
                out[at] = lit;
            }
            if (out[0].length() == 0) out[0] = cur.toString();
        }
        if (!lmReady && cur.length() == 0)
            out[1] = lmStatus.length() > 0 ? lmStatus : "Model yükleniyor…";
        for (int i = 0; i < SN; i++) {
            sugg[i].setText(out[i]);
            sugg[i].setVisibility(out[i].length() > 0 || i < 3 ? View.VISIBLE : View.GONE);
        }
    }

    private void consider(java.util.HashMap<String, Double> best, String w, double cost,
                          java.util.HashMap<String, Double> ctx) {
        Double cs = ctx.get(w);
        double sc = uniScore(w) * 0.2 + (cs == null ? 0 : cs * 12.0) - w.length() * 0.002 - cost;
        if (recent.contains(w)) sc += 0.12;
        Double o = best.get(w);
        if (o == null || sc > o) best.put(w, sc);
    }

    /** Önekle başlayan kelimeler (sık kelime listesi veya sıralı sözlükte tarama). */
    private java.util.List<String> rangeWords(String v, int cap) {
        java.util.ArrayList<String> res = new java.util.ArrayList<String>();
        if (v.length() >= 1 && v.length() <= 4) {
            String[] t = topPrefix.get(v);
            if (t != null && cap <= 120) {
                for (String x : t) res.add(x);
                return res;
            }
        }
        String[] arr = vocab;
        int lo = 0, hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid].compareTo(v) < 0) lo = mid + 1; else hi = mid;
        }
        for (int i = lo; i < arr.length && arr[i].startsWith(v) && res.size() < cap; i++) res.add(arr[i]);
        return res;
    }

    private static final java.util.HashMap<Character, Character> DIA = new java.util.HashMap<Character, Character>();
    static {
        DIA.put('c', '\u00e7'); DIA.put('g', '\u011f'); DIA.put('i', '\u0131');
        DIA.put('o', '\u00f6'); DIA.put('s', '\u015f'); DIA.put('u', '\u00fc');
    }

    /** Yazılan kelimenin olası düzeltme biçimleri: {kelime, tür, değişen harf sayısı}. */
    private java.util.List<String[]> variants(String p) {
        java.util.ArrayList<String[]> r = new java.util.ArrayList<String[]>();
        int n = p.length();
        for (int i = 0; i < n; i++) {
            String nb = NB.get(p.charAt(i));
            if (nb == null) continue;
            for (int k = 0; k < nb.length(); k++)
                r.add(new String[]{p.substring(0, i) + nb.charAt(k) + p.substring(i + 1), "sub", "1"});
        }
        for (int i = 0; i + 1 < n; i++) {
            if (p.charAt(i) != p.charAt(i + 1))
                r.add(new String[]{p.substring(0, i) + p.charAt(i + 1) + p.charAt(i) + p.substring(i + 2), "tr", "1"});
        }
        if (n >= 4) {
            for (int i = 0; i < n; i++) r.add(new String[]{p.substring(0, i) + p.substring(i + 1), "del", "1"});
        }
        for (int i = 0; i <= n; i++) {
            for (int k = 0; k < LETTERS.length(); k++)
                r.add(new String[]{p.substring(0, i) + LETTERS.charAt(k) + p.substring(i), "ins", "1"});
        }
        // Türkçe harfsiz yazım: c->ç g->ğ i->ı o->ö s->ş u->ü (hepsinin birleşimleri)
        java.util.ArrayList<Integer> pos = new java.util.ArrayList<Integer>();
        for (int i = 0; i < n; i++) if (DIA.containsKey(p.charAt(i))) pos.add(i);
        if (pos.size() <= 6) {
            for (int mask = 1; mask < (1 << pos.size()); mask++) {
                char[] cs = p.toCharArray();
                int cnt = 0;
                for (int b = 0; b < pos.size(); b++) {
                    if ((mask & (1 << b)) != 0) { cs[pos.get(b)] = DIA.get(cs[pos.get(b)]); cnt++; }
                }
                r.add(new String[]{new String(cs), "dia", String.valueOf(cnt)});
            }
        }
        return r;
    }

    private double costOf(String[] vk) {
        String k = vk[1];
        if (k.equals("dia")) return diaPen * Integer.parseInt(vk[2]);
        return k.equals("sub") ? pen : k.equals("tr") ? pen * 1.2 : pen * 1.6;
    }

    private boolean isKnown(String w) {
        Integer c = words.get(w);
        return (c != null && c > 0) || own.containsKey(w);
    }

    private double pairP(String nxt, String w) {
        if (nxt == null) return 0;
        String line = lmBi.get(w);
        if (line == null) return 0;
        String[] parts = line.split(",");
        double total = 0, hit = 0;
        for (String part : parts) {
            int c = part.lastIndexOf(':');
            if (c <= 0) continue;
            int cnt;
            try { cnt = Integer.parseInt(part.substring(c + 1)); } catch (Exception e) { continue; }
            total += cnt;
            if (part.substring(0, c).equals(nxt)) hit = cnt;
        }
        return total > 0 ? hit / total : 0;
    }

    /** Bağlama göre aday puanları (2 önceki kelime). */
    /** Bağlam için kullanılacak son iki bilinen kelime; bilinmeyen (yanlış yazılmış) kelimeler atlanır. */
    private String[] kctx() {
        if (prev.length() == 0) return new String[]{"", ""};
        java.util.ArrayList<String> k = new java.util.ArrayList<String>();
        for (String w : new String[]{prev3, prev2, prev}) if (w.length() > 0 && isKnown(w)) k.add(w);
        String b = k.size() > 0 ? k.get(k.size() - 1) : "";
        String a = k.size() > 1 ? k.get(k.size() - 2) : "";
        return new String[]{a, b};
    }

    private java.util.HashMap<String, Double> contextFor(String a, String b) {
        java.util.HashMap<String, Double> ctx = new java.util.HashMap<String, Double>();
        String c1 = b.length() > 0 ? b : "^";
        String c2 = b.length() > 0 ? (a.length() > 0 ? a : "^") : "";
        if (c2.length() > 0) addSucc(lmTri.get(c2 + " " + c1), 1.0, ctx);
        addSucc(lmBi.get(c1), 0.45, ctx);
        return ctx;
    }

    /** Yazılan kelime t için en iyi sözlük kelimesi ve puan farkı: {kelime, fark}. */
    private Object[] bestFix(String t, java.util.HashMap<String, Double> ctx, String nxt) {
        boolean known = isKnown(t);
        double st = 0;
        if (known) {
            Double ct = ctx.get(t);
            st = uniScore(t) * 0.2 + 12.0 * (ct == null ? 0 : ct) + 12.0 * pairP(nxt, t);
        }
        String bw = null;
        double bs = -1e9;
        for (String[] vk : variants(t)) {
            String v = vk[0];
            if (v.length() < 2 || v.equals(t)) continue;
            Integer cnt = words.get(v);
            if (cnt == null || cnt < 100) continue;
            Double cv = ctx.get(v);
            double sv = uniScore(v) * 0.2 + 12.0 * (cv == null ? 0 : cv) + 12.0 * pairP(nxt, v) - costOf(vk);
            if (sv > bs) { bs = sv; bw = v; }
        }
        if (bw == null) return null;
        return new Object[]{bw, known ? Double.valueOf(bs - st) : Double.valueOf(99.0)};
    }

    /** Boşluğa basılınca o anki kelime için düzeltme: dokunma izi + sıklık + bağlam. */

    // ------------------------------------------------ yapışık / hatalı yazılmış birden çok kelime

    /** Boşluksuz yazılmış uzun diziyi 2-4 kelimeye böler (harf hatalarını ve fazladan bir harfi tolere eder). */
    private String[] segment(String p) {
        int n = p.length();
        if (!lmReady || n < 8 || n > 28) return null;
        float[][] ll = tapsFor(p, false);
        final int MAXW = 16;
        // her başlangıç için olası kelimeler: {kelime, değer, dokunma, bitiş}
        java.util.ArrayList<java.util.ArrayList<Object[]>> cand = new java.util.ArrayList<java.util.ArrayList<Object[]>>();
        for (int i = 0; i < n; i++) {
            java.util.ArrayList<Object[]> cl = new java.util.ArrayList<Object[]>();
            String[] bp = new String[]{""};
            double[] bs = new double[]{0};
            int bn = 1;
            for (int k = 0; k < Math.min(MAXW, n - i) && bn > 0; k++) {
                int[] ord = topLetters(ll[i + k], 6);
                String[] np = new String[bn * 6];
                double[] ns = new double[bn * 6];
                int nn = 0;
                for (int b = 0; b < bn; b++) {
                    for (int j = 0; j < 6; j++) {
                        double l = ll[i + k][ord[j]];
                        if (l < -4.0 && j > 0) break;
                        String q = bp[b] + L32.charAt(ord[j]);
                        if (!hasPrefix(q)) continue;
                        np[nn] = q;
                        ns[nn] = bs[b] + l;
                        nn++;
                    }
                }
                if (nn == 0) { bn = 0; break; }
                final double[] nsf = ns;
                Integer[] ix = new Integer[nn];
                for (int j = 0; j < nn; j++) ix[j] = j;
                java.util.Arrays.sort(ix, new java.util.Comparator<Integer>() {
                    @Override public int compare(Integer a, Integer b) { return Double.compare(nsf[b], nsf[a]); }
                });
                int keep = Math.min(30, nn);
                bp = new String[keep];
                bs = new double[keep];
                for (int j = 0; j < keep; j++) { bp[j] = np[ix[j]]; bs[j] = ns[ix[j]]; }
                bn = keep;
                for (int b = 0; b < bn; b++) {
                    String q = bp[b];
                    Integer wc = words.get(q);
                    if (wc == null && !own.containsKey(q)) continue;
                    if (q.length() < 3 && (wc == null || wc < 50000)) continue;
                    double v = bs[b] + tWp * priorOf(q);
                    cl.add(new Object[]{q, v, bs[b], i + k + 1});
                }
            }
            java.util.Collections.sort(cl, new java.util.Comparator<Object[]>() {
                @Override public int compare(Object[] a, Object[] b) { return Double.compare((Double) b[1], (Double) a[1]); }
            });
            while (cl.size() > 8) cl.remove(cl.size() - 1);
            cand.add(cl);
        }
        // durum: {kelime listesi, puan, dokunma toplamı, son kelime}
        java.util.ArrayList<java.util.ArrayList<Object[]>> st = new java.util.ArrayList<java.util.ArrayList<Object[]>>();
        for (int i = 0; i <= n; i++) st.add(new java.util.ArrayList<Object[]>());
        st.get(0).add(new Object[]{new java.util.ArrayList<String>(), 0.0, 0.0, ""});
        java.util.HashMap<String, java.util.HashMap<String, Double>> cc = new java.util.HashMap<String, java.util.HashMap<String, Double>>();
        for (int pos = 0; pos < n; pos++) {
            java.util.ArrayList<Object[]> cs = st.get(pos);
            java.util.Collections.sort(cs, new java.util.Comparator<Object[]>() {
                @Override public int compare(Object[] a, Object[] b) { return Double.compare((Double) b[1], (Double) a[1]); }
            });
            while (cs.size() > 25) cs.remove(cs.size() - 1);
            for (Object[] state : cs) {
                @SuppressWarnings("unchecked")
                java.util.ArrayList<String> ws = (java.util.ArrayList<String>) state[0];
                if (ws.size() >= 4) continue;
                for (int skip = 0; skip <= (ws.isEmpty() ? 0 : 1); skip++) {
                    int start = pos + skip;
                    if (start >= n) continue;
                    String last = (String) state[3];
                    java.util.HashMap<String, Double> cm = null;
                    if (last.length() > 0) {
                        cm = cc.get(last);
                        if (cm == null) { cm = contextFor("", last); cc.put(last, cm); }
                    }
                    for (Object[] c : cand.get(start)) {
                        String w = (String) c[0];
                        double sc = (Double) state[1] + (Double) c[1] - (w.length() <= 2 ? 3.0 : 1.5)
                                - (skip == 1 ? 4.5 : 0.0);
                        if (cm != null) { Double bb = cm.get(w); if (bb != null) sc += tKc * bb; }
                        java.util.ArrayList<String> nw = new java.util.ArrayList<String>(ws);
                        nw.add(w);
                        st.get((Integer) c[3]).add(new Object[]{nw, sc,
                                (Double) state[2] + (Double) c[2] - (skip == 1 ? 4.5 : 0.0), w});
                    }
                }
            }
        }
        Object[] bestS = null;
        for (Object[] state : st.get(n)) {
            @SuppressWarnings("unchecked")
            java.util.ArrayList<String> ws = (java.util.ArrayList<String>) state[0];
            if (ws.size() < 2) continue;
            if (bestS == null || (Double) state[1] > (Double) bestS[1]) bestS = state;
        }
        if (bestS == null) return null;
        if ((Double) bestS[2] < (n < 12 ? -0.3 : -0.7) * n) return null;
        @SuppressWarnings("unchecked")
        java.util.ArrayList<String> ws = (java.util.ArrayList<String>) bestS[0];
        return ws.toArray(new String[0]);
    }

    /** Uzun, tanınmayan, yapışık diziyi kelimelere bölüp yerine yazar. after: kelimeden sonra gelecek karakter. */
    private boolean trySegment(InputConnection ic, String after) {
        if (noFix || !lmReady || cur.length() < 12) return false;
        String orig = cur.toString();
        String lo = orig.toLowerCase(TR);
        if (isKnown(lo) || hasPrefix(lo)) return false;   // gerçek bir kelimenin başıysa bölme
        String[] ws = segment(lo);
        if (ws == null) return false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ws.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(ws[i]);
        }
        String joined = sb.toString();
        if (Character.isUpperCase(orig.charAt(0)))
            joined = joined.substring(0, 1).toUpperCase(TR) + joined.substring(1);
        ic.deleteSurroundingText(orig.length(), 0);
        ic.commitText(joined + after, 1);
        autoRestore = orig;
        autoLearn = lo;          // geri alırsan bu yazımı kelime olarak öğren
        autoFixWord = null;
        autoDel = joined.length() + after.length();
        cur.setLength(0);
        for (String w : ws) learn(w);
        return true;
    }

    private String fixCurrent() {
        if (noFix || !lmReady || cur.length() < 3) return null;
        String p = cur.toString().toLowerCase(TR);
        String[] kc = kctx();
        java.util.HashMap<String, Double> ctx = (prev.length() > 0 && kc[1].length() == 0)
                ? new java.util.HashMap<String, Double>() : contextFor(kc[0], kc[1]);
        java.util.List<Object[]> r = decode(p, ctx, true, false);
        if (r.isEmpty()) return null;
        String w = (String) r.get(0)[0];
        if (w.equals(p)) return null;
        double s1 = (Double) r.get(0)[1], t1 = (Double) r.get(0)[2];
        if (t1 < -6.0) return null;
        float[][] ll = tapsFor(p, false);
        double lit = touchOf(p, ll, p.length());
        boolean known = isKnown(p);
        Integer oc = own.get(p);
        if (oc != null && oc >= 2) return null;          // sen daha önce yazdın: dokunma
        Integer wc = words.get(p);
        double need = Math.max(tMu, 2.0);
        if (known) {
            // doğru yazılmış olabilir: yalnızca çok güçlü kanıtta değiştir
            need = Math.max(tMk, (wc != null && wc >= 3000) ? 9.0 : 6.0);
            if (oc != null) need += 3.0;
        }
        double sl = known ? totalScore(p, lit, p.length(), ctx) : lit;
        return (s1 - sl >= need) ? w : null;
    }


    // ---------------------------------------------------------- ağır hata: bulanık eşleştirme

    private static final java.util.HashMap<Character, float[]> POS = new java.util.HashMap<Character, float[]>();
    static {
        String[] rows = {"qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"};
        float[] off = {0f, 0.5f, 1.0f};
        for (int r = 0; r < rows.length; r++)
            for (int c = 0; c < rows[r].length(); c++)
                POS.put(rows[r].charAt(c), new float[]{c + off[r], r});
    }
    private static final String VOW = "aeıioöuü";

    private int subCost(char a, char b) {
        if (a == b) return 0;
        String nb = NB.get(a);
        if (nb != null && nb.indexOf(b) >= 0) return 40;
        Character da = DIA.get(a);
        if (da != null && da == b) return 15;
        da = DIA.get(b);
        if (da != null && da == a) return 15;
        if (VOW.indexOf(a) >= 0 && VOW.indexOf(b) >= 0) return 65;
        float[] pa = POS.get(a), pb = POS.get(b);
        if (pa != null && pb != null) {
            float dx = pa[0] - pb[0], dy = pa[1] - pb[1];
            if (dx * dx + dy * dy <= 5.29f) return 70;
        }
        return 100;
    }

    /** Klavye komşuluğuna duyarlı düzenleme uzaklığı (yer değiştirme dahil); 100 = bir harf. */
    private int wdist(String t, String w, int limit) { return wdist(t, w, limit, false); }

    private int wdist(String t, String w, int limit, boolean prefix) {
        int n = t.length(), m = w.length();
        if (m > 40 || n > 40) return 1000000;
        int[] p2 = dA, p1 = dB, cu = dC;
        for (int j = 0; j <= m; j++) p1[j] = j * 70;
        for (int i = 1; i <= n; i++) {
            cu[0] = i * 70;
            int rm = cu[0];
            char ti = t.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int c = p1[j - 1] + subCost(ti, w.charAt(j - 1));
                int d = p1[j] + 70;
                if (d < c) c = d;
                d = cu[j - 1] + 70;
                if (d < c) c = d;
                if (i > 1 && j > 1 && ti == w.charAt(j - 2) && t.charAt(i - 2) == w.charAt(j - 1)) {
                    d = p2[j - 2] + 60;
                    if (d < c) c = d;
                }
                cu[j] = c;
                if (c < rm) rm = c;
            }
            if (rm > limit) return 1000000;
            int[] tmp = p2; p2 = p1; p1 = cu; cu = tmp;
        }
        if (prefix && m >= n - 2) {
            int best = 1000000;
            for (int j = Math.max(1, n - 2); j <= m; j++) {
                int v = p1[j] + 12 * (m - j);
                if (v < best) best = v;
            }
            return best;
        }
        return p1[m];
    }

    /** Çok harfi yanlış kelime için en iyi adaylar: {kelime, puan, uzaklık}, puanı büyükten küçüğe. */
    private java.util.List<Object[]> fuzzyRank(String g, java.util.HashMap<String, Double> ctx) {
        return fuzzyRank(g, ctx, false);
    }

    private java.util.List<Object[]> fuzzyRank(String g, java.util.HashMap<String, Double> ctx, boolean prefix) {
        java.util.ArrayList<Object[]> top = new java.util.ArrayList<Object[]>();
        int cl = g.length();
        if (!lmReady || cl < 4 || cl > 14) return top;
        char c0 = g.charAt(0);
        String nb = NB.get(c0);
        Character dc = DIA.get(c0);
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (int pass = 0; pass < 3; pass++) {
            java.util.Collection<String> src;
            if (pass == 0) src = java.util.Arrays.asList(freqWords);
            else if (pass == 1) src = own.keySet();
            else src = ctx.keySet();
            for (String x : src) {
                int lx = x.length();
                if (lx < 3) continue;
                if (pass == 2) {
                    if (Math.abs(lx - cl) > 4 || !isKnown(x)) continue;
                } else {
                    if (prefix ? (lx < cl - 3 || lx > cl + 8) : Math.abs(lx - cl) > 3) continue;
                    char f = x.charAt(0);
                    boolean okf = f == c0 || (nb != null && nb.indexOf(f) >= 0) || (dc != null && dc == f);
                    if (!okf) {
                        Character df = DIA.get(f);
                        okf = df != null && df == c0;
                    }
                    if (!okf) continue;
                }
                if (pass > 0 && !seen.add(x)) continue;
                if (pass == 0) seen.add(x);
                int d = wdist(g, x, (pass == 2 ? 80 : 65) * cl, prefix);
                if (d >= 1000000) continue;
                Double cx = ctx.get(x);
                double c = cx == null ? 0 : cx;
                if (d > fzlim * cl && c <= 0) continue;
                double sc = uniScore(x) * 0.2 + fzc * c - x.length() * 0.002 - d / 100.0 * fzk;
                int pos = top.size();
                while (pos > 0 && (Double) top.get(pos - 1)[1] < sc) pos--;
                if (pos < 5) {
                    top.add(pos, new Object[]{x, sc, d});
                    if (top.size() > 5) top.remove(5);
                }
            }
        }
        return top;
    }

    private void rank(String[] out, double[] sc, String w, double s) {
        int n = out.length;
        for (int i = 0; i < n; i++) if (out[i].equals(w)) return;
        for (int i = 0; i < n; i++) {
            if (s > sc[i]) {
                for (int j = n - 1; j > i; j--) { sc[j] = sc[j - 1]; out[j] = out[j - 1]; }
                sc[i] = s;
                out[i] = w;
                return;
            }
        }
    }

    private void pickSuggestion0(int i) {
        String w = sugg[i].getText().toString();
        if (w.length() == 0) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        syncFromText();   // yanlış silmemek için gerçek durumu oku
        markOwn();
        autoRestore = null;
        autoLearn = null;
        autoDel = 0;
        if (cur.length() > 0) {
            if (w.length() == cur.length()) learnBias(w.toLowerCase(TR));
            recordCorr(cur.toString(), w);
            ic.deleteSurroundingText(cur.length(), 0);
        }
        ic.commitText(w + " ", 1);
        cur.setLength(0);
        learn(w);
        updateSuggestions();
        if (shift == 1) { shift = 0; refreshLabels(); }
    }

    private void paintNav() {
        try {
            android.view.Window w = getWindow().getWindow();
            if (w == null) return;
            w.setNavigationBarColor(0xFF0B0B0F);
            View d = w.getDecorView();
            d.setSystemUiVisibility(d.getSystemUiVisibility()
                    & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } catch (Exception ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        loadPersonal();
        loadLmAsync();
        paintNav();
    }

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0B0F"));
        root.setPadding(dp(2), dp(2), dp(2), dp(6));

        // Üst çubuk: öneriler (veya durum yazısı) + mikrofon
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        bar = new FrameLayout(this);
        suggRow = new LinearLayout(this);
        suggRow.setOrientation(LinearLayout.HORIZONTAL);
        int slotW = (getResources().getDisplayMetrics().widthPixels - dp(46)) / 3;
        for (int i = 0; i < SN; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setTextColor(Color.WHITE);
            t.setTextSize(19);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setMinWidth(slotW);
            t.setPadding(dp(14), 0, dp(14), 0);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickSuggestion(idx); }
            });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    String w = sugg[idx].getText().toString().toLowerCase(TR);
                    if (w.length() == 0) return true;
                    if (forgetWord(w)) {
                        android.widget.Toast.makeText(VoiceIME.this, "Hafızadan silindi: " + w,
                                android.widget.Toast.LENGTH_SHORT).show();
                        updateSuggestions();
                    } else {
                        android.widget.Toast.makeText(VoiceIME.this, "Bu kelimeyi sen öğretmedin, silinecek bir şey yok",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            });
            sugg[i] = t;
            suggRow.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hsv.addView(suggRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.addView(hsv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = label(16, Color.parseColor("#64D2FF"));
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        status.setPadding(dp(10), 0, dp(6), 0);
        status.setMaxLines(2);
        status.setVisibility(View.GONE);
        bar.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        top.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        micBtn = new FrameLayout(this);
        paintMic(false);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_mic);
        int p = dp(9);
        icon.setPadding(p, p, p, p);
        micBtn.addView(icon, new FrameLayout.LayoutParams(dp(46), dp(46)));
        micBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    try { onMicTap(); } catch (Throwable t) { reportErr(t); }
                }
                return true;
            }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(46), dp(46));
        mlp.leftMargin = dp(4);
        mlp.rightMargin = dp(6);
        top.addView(micBtn, mlp);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        keysBox = new LinearLayout(this);
        keysBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(keysBox, wrapWidth());
        buildKeys();
        updateSuggestions();
        return root;
    }


    // --- emoji paneli
    private static final String[] EMO_TAB = {"🕘", "😀", "👍", "💙", "🐱", "🍕", "⚽", "💡"};
    private static final String[] EMO = {
        "",
        "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 😴 🤤 😷 🤒 🤕 🤢 🤮 🥵 🥶 😵 🤯 🤠 🥳 😎 🤓 🧐",
        "👍 👎 👌 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ✋ 🤚 👋 👏 🙌 👐 🤲 🙏 🤝 💪 💅 👀 👅 👄 🧠 👶 👦 👧 👨 👩 👴 👵 🙋 🤷 🤦 🙆 🙅 💁 🙇 🚶 🏃 💃 🕺 👪 💑 💏",
        "\u2764\uFE0F 💙 💚 💛 🧡 💜 🖤 🤍 🤎 💔 💕 💞 💓 💗 💖 💘 💝 💯 💢 💥 💫 💦 💨 🔥 ⭐ 🌟 ✨ ⚡ ✅ ❌ ❓ ❗ ⭕ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🔔 🎵 🎶 🆗 🆕 🔝",
        "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🐢 🐍 🦎 🐙 🦑 🦐 🦀 🐠 🐟 🐬 🐳 🦈 🐊 🐘 🦒 🐪 🌳 🌲 🌴 🌵 🌷 🌹 🌺 🌻 🌼 🍀 🍁 🍂 🍃 🌈 🌞 🌙 🌊",
        "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🍆 🥔 🥕 🌽 🥒 🥦 🍄 🥜 🍞 🥐 🥖 🧀 🍳 🥞 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🍤 🍙 🍚 🍦 🍧 🍨 🍩 🍪 🎂 🍰 🧁 🍫 🍬 🍭 🍮 🍯 ☕ 🍵 🥛 🍺 🍻 🥂 🍷 🥃 🍹",
        "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛳ 🎯 🎮 🎲 🎭 🎨 🎤 🎧 🎸 🎹 🥁 🎬 🏆 🥇 🥈 🥉 🚗 🚕 🚙 🚌 🚓 🚑 🚒 🚚 🚜 🚲 🚀 🚁 🚢 🚂 🏠 🏢 🕌 🌍 ⛺ 🏖 🌅 🎉 🎊 🎁 🎈",
        "⌚ 📱 💻 📷 📹 📺 📻 ⏰ ⏳ 💡 🔦 💰 💵 💳 💎 🔧 🔨 🔑 🔒 🔓 📌 📎 📝 📖 📚 📅 📞 📧 📨 📦 🛒 🚪 🛏 🚿 💊 💉 🩺 🕋 🧴 🧼 🪑 🚽 🔋 🔌 📡 🧭"
    };
    private int emoCat = 1;

    private java.util.List<String> recentEmojis() {
        String r = getSharedPreferences("sesli", MODE_PRIVATE).getString("emo", "");
        java.util.ArrayList<String> l = new java.util.ArrayList<String>();
        for (String x : r.split(" ")) if (x.length() > 0) l.add(x);
        return l;
    }

    private void rememberEmoji(String e) {
        java.util.List<String> l = recentEmojis();
        l.remove(e);
        l.add(0, e);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < 32; i++) {
            if (i > 0) sb.append(' ');
            sb.append(l.get(i));
        }
        getSharedPreferences("sesli", MODE_PRIVATE).edit().putString("emo", sb.toString()).apply();
    }

    private void typeEmoji(String e) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        markOwn();
        if (cur.length() > 0) {
            learn(cur.toString());
            cur.setLength(0);
        }
        ic.commitText(e, 1);
        rememberEmoji(e);
    }

    private void buildEmoji() {
        java.util.List<String> list;
        if (emoCat == 0) list = recentEmojis();
        else list = java.util.Arrays.asList(EMO[emoCat].split(" "));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView head = label(14, Color.parseColor("#8E8E93"));
        String[] names = {"SON KULLANILANLAR", "YÜZLER", "EL VE İNSANLAR", "KALP VE İŞARETLER",
                "HAYVAN VE DOĞA", "YİYECEK VE İÇECEK", "SPOR VE ULAŞIM", "EŞYALAR"};
        head.setText(names[Math.max(0, Math.min(names.length - 1, emoCat))]);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(dp(10), dp(8), 0, dp(2));
        col.addView(head);
        int per = 8;
        if (list.isEmpty()) {
            TextView hint = label(16, Color.parseColor("#AEAEB2"));
            hint.setText("Henüz yok. Kullandığın emojiler burada çıkar.");
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, dp(40), 0, 0);
            col.addView(hint);
        }
        for (int i = 0; i < list.size(); i += per) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < per; j++) {
                TextView e = new TextView(this);
                e.setGravity(Gravity.CENTER);
                e.setTextSize(28);
                e.setIncludeFontPadding(false);
                if (i + j < list.size()) {
                    final String em = list.get(i + j);
                    e.setText(em);
                    e.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { typeEmoji(em); }
                    });
                }
                row.addView(e, new LinearLayout.LayoutParams(0, dp(46), 1f));
            }
            col.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        }
        final float[] dn = new float[2];
        sv.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) { dn[0] = ev.getX(); dn[1] = ev.getY(); }
                else if (a == MotionEvent.ACTION_UP) {
                    float dx = ev.getX() - dn[0], dy = ev.getY() - dn[1];
                    if (Math.abs(dx) > dp(70) && Math.abs(dx) > 2 * Math.abs(dy)) {
                        int nc = emoCat + (dx < 0 ? 1 : -1);
                        if (nc >= 0 && nc < EMO_TAB.length) { emoCat = nc; buildKeys(); }
                    }
                }
                return false;
            }
        });
        keysBox.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(196)));

        LinearLayout b = newRow();
        b.addView(mkKey("ABC", 1.5f, true, new Runnable() {
            @Override public void run() { page = 0; buildKeys(); }
        }, false));
        for (int i = 0; i < EMO_TAB.length; i++) {
            final int idx = i;
            TextView t = mkKey(EMO_TAB[i], 1f, idx == emoCat, new Runnable() {
                @Override public void run() { emoCat = idx; buildKeys(); }
            }, false);
            t.setTextSize(18);
            b.addView(t);
        }
        b.addView(mkKey("⌫", 1.5f, true, new Runnable() {
            @Override public void run() { doDelete(); }
        }, true));
    }


    /** Klavyelerdeki gibi düz çizgili gülen yüz simgesi (renkli emoji değil). */
    private static class SmileyDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float r = Math.min(b.width(), b.height()) * 0.30f;
            pt.setColor(Color.WHITE);
            pt.setStyle(android.graphics.Paint.Style.STROKE);
            pt.setStrokeWidth(r * 0.14f);
            pt.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            c.drawCircle(cx, cy, r, pt);
            c.drawArc(new android.graphics.RectF(cx - r * 0.55f, cy - r * 0.45f, cx + r * 0.55f, cy + r * 0.55f),
                    25, 130, false, pt);
            pt.setStyle(android.graphics.Paint.Style.FILL);
            c.drawCircle(cx - r * 0.36f, cy - r * 0.28f, r * 0.11f, pt);
            c.drawCircle(cx + r * 0.36f, cy - r * 0.28f, r * 0.11f, pt);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Dokunma anında çalışan tuş: hızlı ve kaçırmaz (aralarda ölü boşluk yok). */
    private TextView mkKey(String text, float weight, boolean func, final Runnable action,
                           final boolean repeat) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(text.length() > 1 ? 17 : 23);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        final int base = func ? KEY_FUNC : KEY_BG;
        final GradientDrawable g = new GradientDrawable();
        g.setColor(base);
        g.setCornerRadius(dp(9));
        t.setBackground(new android.graphics.drawable.InsetDrawable(
                g, dp(2), dp(3), dp(2), dp(3)));
        final int[] cnt = {0};
        final Runnable rep = new Runnable() {
            @Override public void run() {
                cnt[0]++;
                int times = cnt[0] > 30 ? 4 : cnt[0] > 18 ? 3 : cnt[0] > 8 ? 2 : 1;
                for (int i = 0; i < times; i++) action.run();
                h.postDelayed(this, cnt[0] > 8 ? 35 : 55);
            }
        };
        t.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        g.setColor(KEY_DOWN);
                        tapX = e.getRawX();
                        tapY = e.getRawY();
                        tapFresh = true;
                        action.run();
                        cnt[0] = 0;
                        if (repeat) h.postDelayed(rep, 380);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        g.setColor(base);
                        h.removeCallbacks(rep);
                        return true;
                    default:
                        return true;
                }
            }
        });
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight));
        return t;
    }

    private LinearLayout newRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        keysBox.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return r;
    }

    private String cased(String k) {
        return shift > 0 ? k.toUpperCase(TR) : k;
    }

    private void refreshLabels() {
        for (int i = 0; i < letterViews.size(); i++)
            letterViews.get(i).setText(cased(letterBase.get(i)));
        if (shiftKey != null) {
            shiftKey.setText(shift == 2 ? "⇪" : "⇧");
            shiftKey.setTextColor(shift > 0 ? Color.parseColor("#0A84FF") : Color.WHITE);
        }
    }

    private void buildKeys() {
        if (keysBox == null) return;
        keysBox.removeAllViews();
        centersOk = false;
        letterViews.clear();
        letterBase.clear();
        shiftKey = null;
        if (page == 2) { buildEmoji(); return; }
        String[][] rows = page == 0 ? LET : SYM;

        for (int i = 0; i < 3; i++) {
            LinearLayout r = newRow();
            if (i == 2 && page == 0) {
                shiftKey = mkKey("⇧", 1.5f, true, new Runnable() {
                    @Override public void run() {
                        long now = System.currentTimeMillis();
                        if (now - lastShiftTap < 350) shift = 2;
                        else shift = (shift == 0) ? 1 : 0;
                        lastShiftTap = now;
                        refreshLabels();
                    }
                }, false);
                r.addView(shiftKey);
            }
            for (final String k : rows[i]) {
                final TextView[] holder = new TextView[1];
                TextView kv = mkKey(page == 0 ? cased(k) : k, 1f, false, new Runnable() {
                    @Override public void run() {
                        typeKey(page == 0 ? cased(k) : k);
                    }
                }, false);
                holder[0] = kv;
                if (page == 0) { letterViews.add(kv); letterBase.add(k); }
                r.addView(kv);
            }
            if (i == 2) {
                r.addView(mkKey("⌫", 1.5f, true, new Runnable() {
                    @Override public void run() { doDelete(); }
                }, true));
            }
        }

        LinearLayout b = newRow();
        b.addView(mkKey(page == 0 ? "123" : "ABC", 1.4f, true, new Runnable() {
            @Override public void run() { page = 1 - page; buildKeys(); }
        }, false));
        TextView emo = mkKey("", 1.3f, true, new Runnable() {
            @Override public void run() { page = 2; emoCat = recentEmojis().isEmpty() ? 1 : 0; buildKeys(); }
        }, false);
        emo.setForeground(new SmileyDrawable());
        b.addView(emo);
        TextView spaceKey = mkKey("Lidya", 5f, false, new Runnable() {
            @Override public void run() { doSpace(); }
        }, false);
        // boşluk tuşunda parmağı sağa/sola kaydır: imleç harf harf gezinir; kısa dokunuş boşluk yazar
        spaceKey.setOnTouchListener(new View.OnTouchListener() {
            float startX, lastStepX;
            boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lastStepX = e.getRawX();
                        moved = false;
                        v.setAlpha(0.7f);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float step = dp(13);
                        float dx = e.getRawX() - lastStepX;
                        if (!moved && Math.abs(e.getRawX() - startX) < dp(10)) return true;
                        while (Math.abs(dx) >= step) {
                            moved = true;
                            sliding = true;
                            int code = dx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
                            markOwn();
                            sendDownUpKeyEvents(code);
                            lastStepX += dx > 0 ? step : -step;
                            dx = e.getRawX() - lastStepX;
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        v.setAlpha(1f);
                        sliding = false;
                        if (moved) {
                            cur.setLength(0);
                            taps.clear();
                            tapPts.clear();
                            autoRestore = null;
                            autoLearn = null;
                            autoDel = 0;
                            syncFromText();
                            updateSuggestions();
                        } else {
                            tapX = e.getRawX();
                            tapY = e.getRawY();
                            doSpace();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        sliding = false;
                        v.setAlpha(1f);
                        return true;
                    default:
                        return true;
                }
            }
        });
        b.addView(spaceKey);
        b.addView(mkKey(".", 1f, false, new Runnable() {
            @Override public void run() { typeKey("."); }
        }, false));
        b.addView(mkKey("↵", 1.4f, true, new Runnable() {
            @Override public void run() {
                markOwn();
                learn(cur.toString());
                cur.setLength(0);
                prev = ""; prev2 = "";
                if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
                updateSuggestions();
            }
        }, false));
    }

    private void leaveStatus() {
        if (!listening && status != null && status.getVisibility() == View.VISIBLE) {
            status.setVisibility(View.GONE);
            suggRow.setVisibility(View.VISIBLE);
        }
    }

    private void typeKey0(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        markOwn();
        autoRestore = null;
        autoLearn = null;
        autoDel = 0;
        boolean fresh = tapFresh;
        tapFresh = false;
        boolean letter = Character.isLetter(s.charAt(0));
        boolean apos = s.equals("'") || s.equals("\u2019");
        if (!letter && !apos && !afterApos && cur.length() >= 3) {
            // nokta, soru işareti vb. de kelimeyi bitirir: yazım düzeltmesi burada da çalışsın
            String fix = fixCurrent();
            if (fix != null) {
                String orig = cur.toString();
                if (Character.isUpperCase(orig.charAt(0)))
                    fix = fix.substring(0, 1).toUpperCase(TR) + fix.substring(1);
                ic.deleteSurroundingText(orig.length(), 0);
                ic.commitText(fix, 1);
                recordCorr(orig, fix);
                cur.setLength(0);
                cur.append(fix);
            } else if (cur.length() >= 8 && trySegment(ic, ".?!".contains(s) ? s + " " : s)) {
                autoRestore = null;
                autoDel = 0;
                leaveStatus();
                if ((s.equals(".") || s.equals("?") || s.equals("!"))) { prev = ""; prev2 = ""; }
                updateSuggestions();
                return;
            }
        }
        boolean endP = s.equals(".") || s.equals("?") || s.equals("!");
        boolean autoSp = false;
        if (endP) {
            CharSequence pc = ic.getTextBeforeCursor(1, 0);
            autoSp = pc != null && pc.length() == 1 && Character.isLetter(pc.charAt(0));
        }
        ic.commitText(autoSp ? s + " " : s, 1);
        leaveStatus();
        if (apos && cur.length() > 0) {
            // özel isim + ek (Ahmet'e): isim bağlamda kalsın, ek öğrenilmesin/düzeltilmesin
            prev3 = prev2;
            prev2 = prev;
            prev = cur.toString().toLowerCase(TR);
            cur.setLength(0);
            afterApos = true;
            updateSuggestions();
            return;
        }
        if (letter) {
            if (cur.length() == 0) { taps.clear(); tapPts.clear(); }
            taps.add(fresh ? tapVec(tapX, tapY) : null);
            tapPts.add(fresh ? new float[]{tapX, tapY} : null);
            cur.append(s);
            if (shift == 1) { shift = 0; refreshLabels(); }
        } else {
            if (!afterApos) learn(cur.toString());
            afterApos = false;
            cur.setLength(0);
            if (s.equals(".") || s.equals("?") || s.equals("!")) { prev = ""; prev2 = ""; }
            if ((s.equals(".") || s.equals("?") || s.equals("!")) && page == 0 && shift != 2) {
                shift = 1;
                refreshLabels();
            }
        }
        updateSuggestions();
    }

    private void doSpace0() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        markOwn();
        autoRestore = null;
        autoLearn = null;
        autoDel = 0;
        if (afterApos) {
            ic.commitText(" ", 1);
            afterApos = false;
            cur.setLength(0);
            leaveStatus();
            updateSuggestions();
            return;
        }
        if (cur.length() == 0) {
            CharSequence t3 = ic.getTextBeforeCursor(2, 0);
            if (t3 != null && t3.length() == 2 && ".?!".indexOf(t3.charAt(0)) >= 0 && t3.charAt(1) == ' ') {
                if (page == 0 && shift != 2) { shift = 1; refreshLabels(); }
                return;   // noktadan sonra zaten boşluk var
            }
        }
        String wPrev = prev, wPrev2 = prev2, wPrev3 = prev3;
        String fix = fixCurrent();
        if (fix == null && trySegment(ic, " ")) {
            leaveStatus();
            updateSuggestions();
            return;
        }
        String finalWord;
        if (fix != null) {
            String orig = cur.toString();
            if (Character.isUpperCase(orig.charAt(0)))
                fix = fix.substring(0, 1).toUpperCase(TR) + fix.substring(1);
            ic.deleteSurroundingText(orig.length(), 0);
            ic.commitText(fix + " ", 1);
            autoRestore = orig;
            autoLearn = orig.toLowerCase(TR);
            autoDel = fix.length() + 1;
            autoFixWord = fix.toLowerCase(TR);
            recordCorr(orig, fix);
            finalWord = fix;
        } else {
            ic.commitText(" ", 1);
            finalWord = cur.toString();
            String lw = finalWord.toLowerCase(TR);
            if (lw.length() >= 3 && isKnown(lw) && !corrWord.containsKey(lw)) learnBias(lw);
        }
        leaveStatus();
        learn(finalWord);
        cur.setLength(0);
        {
            CharSequence t2 = ic.getTextBeforeCursor(2, 0);
            if (page == 0 && shift != 2 && t2 != null && t2.length() == 2
                    && ".?!".indexOf(t2.charAt(0)) >= 0 && t2.charAt(1) == ' ') {
                shift = 1;
                refreshLabels();
            }
        }

        // geriye dönük: önceki kelimeyi, yeni yazılan kelimeye bakarak sına
        String fw = finalWord.toLowerCase(TR);
        if (!noFix && lmReady && wPrev.length() >= 3 && fw.length() >= 1 && !isKnown(wPrev)) {
            java.util.HashMap<String, Double> ctxW = contextFor(wPrev3, wPrev2);
            Object[] r = bestFix(wPrev, ctxW, fw);
            if (r != null && (Double) r[1] > retroM) {
                String rep = (String) r[0];
                String segLow = wPrev + " " + fw + " ";
                CharSequence tail = ic.getTextBeforeCursor(segLow.length(), 0);
                if (tail != null && tail.toString().toLowerCase(TR).equals(segLow)) {
                    String seg = tail.toString();
                    if (Character.isUpperCase(seg.charAt(0)))
                        rep = rep.substring(0, 1).toUpperCase(TR) + rep.substring(1);
                    String nw = rep + seg.substring(wPrev.length());
                    ic.deleteSurroundingText(seg.length(), 0);
                    ic.commitText(nw, 1);
                    autoRestore = seg;
                    autoDel = nw.length();
                    autoLearn = wPrev;
                    prev2 = rep.toLowerCase(TR);
                }
            }
        }
        updateSuggestions();
    }

    private void doDelete0() {
        markOwn();
        InputConnection ic = getCurrentInputConnection();
        if (autoRestore != null && autoDel > 0 && ic != null) {
            // otomatik düzeltmeyi geri al: yazdığın haline döner
            ic.deleteSurroundingText(autoDel, 0);
            ic.commitText(autoRestore, 1);
            if (autoLearn != null && autoFixWord != null) forgetCorr(autoLearn, autoFixWord);
            autoFixWord = null;
            if (autoLearn != null && isWord(autoLearn)) {
                Integer c = own.get(autoLearn);
                own.put(autoLearn, (c == null ? 0 : c) + 2);   // bir daha düzeltme
                if (!words.containsKey(autoLearn)) words.put(autoLearn, 0);
            }
            autoRestore = null;
            autoLearn = null;
            autoDel = 0;
            syncFromText();
            updateSuggestions();
            return;
        }
        delVer++;
        boolean wasEmpty = cur.length() == 0;
        if (cur.length() > 0) {
            cur.setLength(cur.length() - 1);
            if (!taps.isEmpty()) taps.remove(taps.size() - 1);
            if (!tapPts.isEmpty()) tapPts.remove(tapPts.size() - 1);
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        updateSuggestions();
        if (wasEmpty) {
            // önceki kelimenin üstüne geldik: harf harf silerken öneriler o kelimeye göre hesaplansın
            final int ver = ++delVer;
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (ver != delVer || listening) return;
                    markOwn();
                    syncFromText();
                    updateSuggestions();
                }
            }, 45);
        }
    }

    private void autoShift() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence b = ic.getTextBeforeCursor(3, 0);
        String t = b == null ? "" : b.toString().trim();
        boolean cap = t.isEmpty() || t.endsWith(".") || t.endsWith("?") || t.endsWith("!");
        int ns = cap ? 1 : 0;
        if (shift != 2 && ns != shift) { shift = ns; refreshLabels(); }
    }

    private LinearLayout.LayoutParams wrapWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView label(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void paintMic(boolean active) {
        if (micBtn == null) return;
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, active ? RED : BLUE);
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(3), 0x66FFFFFF);
        micBtn.setBackground(g);
    }

    private void setStatus(String s) {
        if (status == null) return;
        if (s.startsWith("Konuşmak için")) {
            status.setVisibility(View.GONE);
            suggRow.setVisibility(View.VISIBLE);
            return;
        }
        status.setText(s);
        suggRow.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
    }

    private void setPartial(String s) {
        if (status == null) return;
        if (s.length() > 0) {
            status.setText(s);
            suggRow.setVisibility(View.GONE);
            status.setVisibility(View.VISIBLE);
        } else if (listening) {
            status.setText("Dinliyorum… konuş");
        }
    }

    private void updateDiag() {
        if (diag == null) return;
        String rec = (candIdx >= 0 && candIdx < cands.size()) ? shortName(cands.get(candIdx)) : "-";
        diag.setText("Tanıyıcı: " + rec + "   Son hata: " + lastErr);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Silme tuşuna basılı tutunca tekrar tekrar siler. */
    private class RepeatTouch implements View.OnTouchListener {
        private final Runnable repeat = new Runnable() {
            @Override public void run() {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                h.postDelayed(this, 60);
            }
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(0.6f);
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                    h.postDelayed(repeat, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    h.removeCallbacks(repeat);
                    return true;
                default:
                    return true;
            }
        }
    }

    // ======================================================== klavye yaşamı
    private void onStartInputView0(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        h.removeCallbacks(autoStart);
        if (importChanged()) loadPersonal();
        loadBias();

        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int var = info.inputType & InputType.TYPE_MASK_VARIATION;
        password = (cls == InputType.TYPE_CLASS_TEXT
                && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (cls == InputType.TYPE_CLASS_NUMBER
                && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        noFix = cls != InputType.TYPE_CLASS_TEXT
                || var == InputType.TYPE_TEXT_VARIATION_URI
                || var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_FILTER;
        autoRestore = null;
        autoLearn = null;
        autoDel = 0;
        afterApos = false;
        if (password) {
            stopAll();
            setStatus("Şifre kutusunda ses kapalı, elle yaz");
            setPartial("");
            return;
        }
        if (info.inputType == InputType.TYPE_NULL) {
            setStatus("Bu alana yazılamıyor");
            return;
        }
        if (listening || wanted) return;   // zaten dinliyoruz
        setStatus("Konuşmak için mikrofona dokun \u2192");
        setPartial("");
        page = 0;
        syncFromText();
        buildKeys();
        autoShift();
        updateSuggestions();
        paintNav();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        h.removeCallbacks(autoStart);
        stopAll();
        saveWords();
        saveBias();
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        stopAll();
        saveWords();
        super.onDestroy();
    }

    /** Eski klavyene (SwiftKey vb.) döner. */
    private void goBack() {
        stopAll();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                if (switchToPreviousInputMethod()) return;
                String other = findOtherIme(imm);
                if (other != null) {
                    switchInputMethod(other);
                    return;
                }
            } else {
                IBinder token = getWindow().getWindow().getAttributes().token;
                if (imm.switchToLastInputMethod(token)) return;
            }
        } catch (Exception ignored) { }
        imm.showInputMethodPicker();
    }

    private String findOtherIme(InputMethodManager imm) {
        String fallback = null;
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
            String pkg = i.getPackageName();
            if (pkg.equals(getPackageName())) continue;
            if (pkg.contains("voicesearch") || pkg.contains("googlequicksearchbox")) continue;
            if (pkg.contains("swiftkey")) return i.getId();
            if (fallback == null) fallback = i.getId();
        }
        return fallback;
    }

    // ============================================================ dinleme

    private void onMicTap() {
        if (password) return;
        if (wanted) {
            // Durdur: o ana kadar söylenenler yazılır
            stopDictation();
        } else {
            if (listening) finishMic();   // takılı kalmış eski dinleme varsa temizle
            begin();
        }
    }

    private void begin() {
        stopTok++;
        setStatus("Mikrofon açılıyor…");
        silenceRetries = 0;
        lastPartial = "";
        spokeOnce = false;
        lastSpeechAt = android.os.SystemClock.uptimeMillis();
        h.removeCallbacks(idleCheck);
        h.postDelayed(idleCheck, 1000);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            lastErr = "mikrofon izni yok";
            updateDiag();
            setStatus("Mikrofon izni yok. Lidya Klavye uygulamasını açıp izin ver");
            return;
        }
        buildCandidates();
        if (cands.isEmpty()) {
            setStatus("Konuşma tanıma servisi yok (Google uygulaması gerekli)");
            return;
        }
        candIdx = 0;
        wanted = true;
        busyRetried = false;
        startRecognizer();
    }

    /** Denenecek tanıyıcılar: önce daha önce çalışan, sonra sistem varsayılanı, Google, diğerleri. */
    private void buildCandidates() {
        cands.clear();
        PackageManager pm = getPackageManager();

        String good = getSharedPreferences("sesli", MODE_PRIVATE).getString("good", null);
        if (good != null) {
            ComponentName c = ComponentName.unflattenFromString(good);
            if (c != null && serviceExists(pm, c)) cands.add(c);
        }
        cands.add(null);   // sistem varsayılanı
        addIfExists(pm, GOOGLE_APP);
        addIfExists(pm, GOOGLE_TTS);
        try {
            List<ResolveInfo> all = pm.queryIntentServices(
                    new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            for (ResolveInfo ri : all) {
                if (ri.serviceInfo == null) continue;
                addIfExists(pm, new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name));
            }
        } catch (Exception ignored) { }
    }

    private boolean serviceExists(PackageManager pm, ComponentName c) {
        try {
            pm.getServiceInfo(c, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void addIfExists(PackageManager pm, ComponentName c) {
        if (!cands.contains(c) && serviceExists(pm, c)) cands.add(c);
    }

    private String shortName(ComponentName c) {
        if (c == null) return "sistem";
        String p = c.getPackageName();
        if (p.contains("googlequicksearchbox")) return "google";
        if (p.contains("google.android.tts")) return "google-tts";
        int i = p.lastIndexOf('.');
        return i >= 0 ? p.substring(i + 1) : p;
    }


    // --- konuşma tanıma iyileştirmeleri
    private String lastPartial = "";
    private Intent recIntent = null;
    private boolean beepMuted = false;
    private int silenceRetries = 0;

    private int unknownCount(String s) {
        int n = 0;
        for (String w : s.toLowerCase(TR).split("[^\\p{L}*]+")) {
            if (w.length() < 2) continue;
            if (w.indexOf('*') >= 0 || !isKnown(w)) n++;
        }
        return n;
    }

    /** Tanıyıcının verdiği alternatifler arasından, bilinmeyen kelimesi en az olanı seçer. */
    /** Bir cümlenin dil modeli puanı: sık kelimeler, kelime çiftleri ve kendi kelimelerin yüksek; bilinmeyen kelime düşük. */
    private double sentenceScore(String sent) {
        String[] ws = sent.toLowerCase(TR).split("[^\\p{L}']+");
        double sc = 0;
        int n = 0;
        String prevW = "";
        for (String w0 : ws) {
            String w = w0.replace("'", "");
            if (w.isEmpty()) continue;
            n++;
            if (isKnown(w)) {
                sc += 0.12 * priorOf(w);
            } else {
                sc -= 3.0;
            }
            if (prevW.length() > 0) {
                java.util.HashMap<String, Double> cm = contextFor("", prevW);
                Double bb = cm.get(w);
                if (bb != null) sc += 2.5 * Math.min(1.0, bb * 4);
            }
            prevW = w;
        }
        return n == 0 ? -99 : sc;
    }

    private String pickBest(Bundle b) {
        try { return pickBest0(b); } catch (Throwable t) { reportErr(t); return first(b); }
    }

    private String pickBest0(Bundle b) {
        java.util.ArrayList<String> l = b == null ? null
                : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (l == null || l.isEmpty()) return null;
        String best = l.get(0);
        if (l.size() == 1 || !lmReady) return best;
        float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        double top = Double.NEGATIVE_INFINITY;
        int bi = 0;
        double s0 = 0;
        for (int i = 0; i < l.size(); i++) {
            double c = (conf != null && i < conf.length && conf[i] >= 0) ? conf[i] : (i == 0 ? 0.8 : 0.5 - 0.05 * i);
            double sc = 4.0 * c + sentenceScore(unmask(l.get(i)));
            if (i == 0) s0 = sc;
            if (sc > top) { top = sc; bi = i; }
        }
        // tanıyıcının ilk tercihini ancak dil modeli açıkça daha iyi bulursa değiştir
        if (bi != 0 && top - s0 > 1.2) best = l.get(bi);
        return best;
    }

    private static final String[][] SPOKEN = {
        {"\\s*(?<!\\p{L})(soru işareti)(?!\\p{L})", "?"},
        {"\\s*(?<!\\p{L})(ünlem işareti|ünlem)(?!\\p{L})", "!"},
        {"\\s*(?<!\\p{L})(virgül)(?!\\p{L})", ","},
        {"\\s*(?<!\\p{L})(iki nokta üst üste)(?!\\p{L})", ":"},
        {"\\s*(?<!\\p{L})(yeni satır|alt satır|satır başı)(?!\\p{L})\\s*", "\n"},
        {"\\s*(?<!\\p{L})nokta\\s*$", "."}};

    /** "virgül", "soru işareti", "yeni satır" gibi söylenen noktalama komutlarını simgeye çevirir. */
    private String spokenPunct(String sp) {
        String r = sp;
        try {
            for (String[] pr : SPOKEN) {
                r = java.util.regex.Pattern.compile(pr[0], java.util.regex.Pattern.CASE_INSENSITIVE
                        | java.util.regex.Pattern.UNICODE_CASE).matcher(r).replaceAll(java.util.regex.Matcher.quoteReplacement(pr[1]));
            }
            return r.trim();
        } catch (Throwable t) {
            return sp;
        }
    }

    /** Sansürlenmiş kelimeleri (s*** gibi) sözlükten en olası kelimeyle tamamlar. */
    private String unmask(String s) {
        if (s == null || s.indexOf('*') < 0 || !lmReady) return s;
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\p{L}*]+").matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            last = m.end();
            String t = m.group();
            if (t.indexOf('*') < 0 || t.replace("*", "").length() == 0) { out.append(t); continue; }
            String low = t.toLowerCase(TR);
            String best = null;
            int bc = -1;
            for (java.util.Map.Entry<String, Integer> e : words.entrySet()) {
                String w = e.getKey();
                if (w.length() != low.length()) continue;
                boolean ok = true;
                for (int i = 0; i < w.length(); i++) {
                    char c = low.charAt(i);
                    if (c != '*' && c != w.charAt(i)) { ok = false; break; }
                }
                if (ok && e.getValue() > bc) { bc = e.getValue(); best = w; }
            }
            if (best == null) { out.append(t); continue; }
            if (Character.isUpperCase(t.charAt(0))) best = best.substring(0, 1).toUpperCase(TR) + best.substring(1);
            out.append(best);
        }
        out.append(s.substring(last));
        return out.toString();
    }


    /** Konuşma tanıyıcının "bip" sesini dinleme boyunca kapatır (kesik kesik açılıp kapanma hissini azaltır). */
    private void muteBeep(boolean on) {
        if (on == beepMuted) return;
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            // telefon sessiz/titreşim modundayken ses ayarına hiç dokunma (sesli moda geçirmesin)
            if (on && am.getRingerMode() != android.media.AudioManager.RINGER_MODE_NORMAL) return;
            int dir = on ? android.media.AudioManager.ADJUST_MUTE : android.media.AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, dir, 0);
            am.adjustStreamVolume(android.media.AudioManager.STREAM_SYSTEM, dir, 0);
            beepMuted = on;
        } catch (Throwable ignored) { }
    }

    /** Aynı tanıyıcıyla, ekranı değiştirmeden yeniden dinlemeye başlar. */
    private int stopTok = 0;
    private long lastSpeechAt = 0;
    private boolean spokeOnce = false;

    /** Mikrofonu tamamen kapatır (ekran ve ses dahil). */
    private void finishMic() {
        h.removeCallbacks(idleCheck);
        listening = false;
        wanted = false;
        session++;
        stopPulse();
        paintMic(false);
        destroyRecognizer();
        muteBeep(false);
        setPartial("");
        setStatus("Konuşmak için mikrofona dokun");
    }

    /** Dokunarak veya sessizlik nedeniyle durdur: o ana kadar duyulanı yaz, sonra kapat. */
    private void stopDictation() {
        h.removeCallbacks(idleCheck);
        wanted = false;
        final int tok = ++stopTok;
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) { }
        }
        setStatus("Yazılıyor…");
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (tok == stopTok && !wanted) {
                    if (lastPartial.length() > 0) { commitFinal(lastPartial); lastPartial = ""; }
                    finishMic();
                }
            }
        }, 1300);
    }

    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            if (!wanted) return;
            long idle = android.os.SystemClock.uptimeMillis() - lastSpeechAt;
            if (idle > (spokeOnce ? 6000 : 10000)) {
                stopDictation();
                return;
            }
            h.postDelayed(this, 1000);
        }
    };

    private void restartInPlace(final int mySession, long delay) {
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (mySession != session) return;
                if (!wanted) { finishMic(); return; }
                if (recognizer == null || recIntent == null) { startRecognizer(); return; }
                lastPartial = "";
                try {
                    recognizer.startListening(recIntent);
                } catch (Throwable t) {
                    startRecognizer();
                }
            }
        }, delay);
    }

    private void startRecognizer() {
        if (getCurrentInputConnection() == null) {
            wanted = false;
            setStatus("Yazı kutusu bulunamadı");
            return;
        }
        destroyRecognizer();
        session++;
        final int mySession = session;
        ComponentName c = cands.get(candIdx);
        updateDiag();

        try {
            recognizer = (c == null)
                    ? SpeechRecognizer.createSpeechRecognizer(this)
                    : SpeechRecognizer.createSpeechRecognizer(this, c);
        } catch (Exception e) {
            handleError(5, mySession);
            return;
        }
        recognizer.setRecognitionListener(new Listener(mySession));

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recIntent = intent;
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra("android.speech.extra.MASK_OFFENSIVE_WORDS", false);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);

        listening = true;
        lastPartial = "";
        muteBeep(true);
        paintMic(true);
        startPulse();
        setStatus("Dinliyorum… konuş");
        setPartial("");
        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            handleError(5, mySession);
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            SpeechRecognizer r = recognizer;
            recognizer = null;
            try {
                r.setRecognitionListener(null);
                r.cancel();
                r.destroy();
            } catch (Exception ignored) { }
        }
    }

    private void stopAll() {
        h.removeCallbacks(idleCheck);
        wanted = false;
        muteBeep(false);
        listening = false;
        session++;
        destroyRecognizer();
        stopPulse();
        paintMic(false);
        setPartial("");
    }

    private void startPulse() {
        stopPulse();
        if (micBtn == null) return;
        pulse = ObjectAnimator.ofPropertyValuesHolder(micBtn,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.1f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.1f));
        pulse.setDuration(600);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (micBtn != null) {
            micBtn.setScaleX(1f);
            micBtn.setScaleY(1f);
        }
    }

    private class Listener implements RecognitionListener {
        private final int mySession;

        Listener(int s) { mySession = s; }

        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (mySession != session) return;
            String s = first(partialResults);
            if (s != null) {
                setPartial(s);
                lastPartial = s;
                spokeOnce = true;
                lastSpeechAt = android.os.SystemClock.uptimeMillis();
            }
        }

        @Override
        public void onResults(Bundle results) {
            if (mySession != session) return;
            String s = pickBest(results);
            if (s == null) s = first(results);
            if (s == null && lastPartial.length() > 0) s = lastPartial;
            if (s != null) {
                commitFinal(s);
                rememberGood();
                silenceRetries = 0;
                spokeOnce = true;
                lastSpeechAt = android.os.SystemClock.uptimeMillis();
            }
            lastPartial = "";
            lastErr = "-";
            updateDiag();
            if (wanted) {
                // sürekli yazım: ekran ve tanıyıcı aynı kalsın, sadece yeniden dinle
                setPartial("");
                restartInPlace(mySession, 40);
                return;
            }
            setPartial("");
            listening = false;
            stopPulse();
            paintMic(false);
            destroyRecognizer();
            muteBeep(false);
            setStatus("Konuşmak için mikrofona dokun");
        }

        @Override
        public void onError(int error) {
            handleError(error, mySession);
        }
    }

    private void rememberGood() {
        if (candIdx < 0 || candIdx >= cands.size()) return;
        ComponentName c = cands.get(candIdx);
        SharedPreferences.Editor e = getSharedPreferences("sesli", MODE_PRIVATE).edit();
        if (c == null) e.remove("good"); else e.putString("good", c.flattenToString());
        e.apply();
    }

    private void handleError(int error, int mySession) {
        if (mySession != session) return;
        // hata gelse bile o ana kadar duyulan yarım cümleyi kaybetme
        boolean salvaged = false;
        if (lastPartial.length() > 0 && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            commitFinal(lastPartial);
            lastPartial = "";
            salvaged = true;
            silenceRetries = 0;
        }
        lastErr = errName(error);
        boolean quiet0 = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH;
        if (wanted && quiet0 && (salvaged || silenceRetries < 40)) {
            if (!salvaged) silenceRetries++;
            setPartial("");
            restartInPlace(mySession, 60);
            return;
        }
        listening = false;
        stopPulse();
        paintMic(false);
        destroyRecognizer();
        setPartial("");

        // sessizlik / anlaşılmadı: mikrofon açıkken birkaç kez daha dinle
        boolean quiet = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH;
        if (wanted && quiet && (salvaged || silenceRetries < 3)) {
            if (!salvaged) silenceRetries++;
            setStatus("Dinliyorum… konuş");
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (wanted && !listening) startRecognizer();
                }
            }, 150);
            return;
        }
        // Tanıyıcı bozuksa sıradakini dene
        boolean switchable = error == 4 || error == 5 || error == 10 || error == 11
                || error == 12 || error == 13;
        if (wanted && switchable && candIdx + 1 < cands.size()) {
            candIdx++;
            updateDiag();
            setStatus("Başka tanıyıcı deneniyor…");
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (wanted && !listening) startRecognizer();
                }
            }, 200);
            return;
        }
        // Meşgulse bir kez tekrar dene
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && wanted && !busyRetried) {
            busyRetried = true;
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (wanted && !listening) startRecognizer();
                }
            }, 600);
            return;
        }

        wanted = false;
        muteBeep(false);
        updateDiag();
        switch (error) {
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
            case SpeechRecognizer.ERROR_NO_MATCH:
                setStatus("Ses duyulmadı. Mikrofona dokunup tekrar konuş");
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                setStatus("İnternet bağlantısı gerekli");
                break;
            case SpeechRecognizer.ERROR_AUDIO:
                setStatus("Mikrofon açılamadı. Başka uygulama kullanıyor olabilir");
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                setStatus("Mikrofon izni yok. Lidya Klavye uygulamasını açıp izin ver");
                break;
            default:
                setStatus("Konuşma tanıma çalışmadı (hata: " + lastErr + ")");
                break;
        }
    }

    private String errName(int e) {
        switch (e) {
            case 1: return "1 ağ zaman aşımı";
            case 2: return "2 ağ";
            case 3: return "3 ses";
            case 4: return "4 sunucu";
            case 5: return "5 istemci";
            case 6: return "6 ses yok";
            case 7: return "7 eşleşme yok";
            case 8: return "8 meşgul";
            case 9: return "9 izin";
            case 10: return "10 çok istek";
            case 11: return "11 sunucu koptu";
            case 12: return "12 dil desteklenmiyor";
            case 13: return "13 dil yok";
            default: return String.valueOf(e);
        }
    }

    private String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return null;
        return list.get(0);
    }

    // ========================================================== yazı yazma

    /** Kesinleşen cümleyi yazı kutusuna yazar (baş harf ve boşluk ayarıyla). */
    private void commitFinal0(String spoken) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            setStatus("Yazı kutusu bulunamadı");
            return;
        }
        String sp = spokenPunct(unmask(spoken).trim());
        if (sp.isEmpty()) return;

        CharSequence before = ic.getTextBeforeCursor(3, 0);
        String b = before == null ? "" : before.toString();
        String t = b.trim();
        boolean cap = t.isEmpty() || t.endsWith(".") || t.endsWith("?") || t.endsWith("!");
        if (cap) {
            sp = sp.substring(0, 1).toUpperCase(new Locale("tr", "TR")) + sp.substring(1);
        }
        String prefix = (!b.isEmpty() && !Character.isWhitespace(b.charAt(b.length() - 1))) ? " " : "";
        sp = withEnding(sp);
        markOwn();
        ic.commitText(prefix + sp + " ", 1);
        cur.setLength(0);
        prev = "";
        prev2 = "";
        prev3 = "";
        hist.clear();
        learnText(sp);
        if (shift == 0) { shift = 1; refreshLabels(); }
        updateSuggestions();
    }

    private static final java.util.regex.Pattern QWORD = java.util.regex.Pattern.compile(
            "(^|\\s)(ne|neden|niçin|niye|nasıl|nerede|nereye|nereden|neresi|kim|kimin|kime|kimi|kiminle|kaç|kaçta|kaçıncı|hangi|hangisi|neler|nelerdir|ne zaman|kaça|kaçı)(\\s|$)");
    private static final java.util.regex.Pattern QSUFFIX = java.util.regex.Pattern.compile(
            "(^|\\s)\\p{L}*\\s?m[ıiuü](yım|yim|yum|yüm|sın|sin|sun|sün|yız|yiz|yuz|yüz|sınız|siniz|sunuz|sünüz|ydı|ydi|ydu|ydü|ymış|ymiş|ymuş|ymüş|yor|yorsun|yoruz|yorlar|y|dır|dir|dur|dür|ş|şsın|şsin|ştı|ştı|ştu|ştü|lar|ler)?$");

    /** Konuşulan cümle noktalama ile bitmediyse nokta (soruysa soru işareti) ekler. */
    private String withEnding(String sp) {
        if (sp.isEmpty()) return sp;
        char last = sp.charAt(sp.length() - 1);
        if (last == '.' || last == '?' || last == '!' || last == '…' || last == ':' || last == ';') return sp;
        String low = sp.toLowerCase(TR);
        String[] ws = low.split("\\s+");
        String lastW = ws[ws.length - 1];
        boolean q = QWORD.matcher(low).find()
                || (lastW.length() <= 12 && lastW.matches("m[ıiuü](yım|yim|yum|yüm|sın|sin|sun|sün|yız|yiz|yuz|yüz|sınız|siniz|sunuz|sünüz|ydı|ydi|ydu|ydü|y)?"));
        return sp + (q ? "?" : ".");
    }

    /** Noktalama: önceki boşluğu silip işareti yazar. */
    private void punctuation(String p) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        if (before != null && before.length() == 1 && before.charAt(0) == ' ') {
            ic.deleteSurroundingText(1, 0);
        }
        ic.commitText(p + " ", 1);
    }

    private void commitRaw(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
    }

    private void typeKey(String s) {
        try { typeKey0(s); } catch (Throwable t) { reportErr(t); }
    }

    private void doSpace() {
        try { doSpace0(); } catch (Throwable t) { reportErr(t); }
    }

    private void doDelete() {
        try { doDelete0(); } catch (Throwable t) { reportErr(t); }
    }

    private final Runnable updRun = new Runnable() {
        @Override public void run() {
            try { updateSuggestions0(); } catch (Throwable t) { reportErr(t); }
        }
    };

    /** Öneriler kısa bir gecikmeyle (hızlı yazarken birleştirilerek) yenilenir; tuş yazımı hiç beklemez. */
    private void updateSuggestions() {
        h.removeCallbacks(updRun);
        h.postDelayed(updRun, 35);
    }

    private void commitFinal(String spoken) {
        try { commitFinal0(spoken); } catch (Throwable t) {
            reportErr(t);
            try {
                InputConnection ic2 = getCurrentInputConnection();
                if (ic2 != null && spoken != null && spoken.trim().length() > 0) ic2.commitText(spoken.trim() + " ", 1);
            } catch (Throwable ignored) { }
        }
    }

    private void pickSuggestion(int i) {
        try { pickSuggestion0(i); } catch (Throwable t) { reportErr(t); }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        try { onStartInputView0(info, restarting); } catch (Throwable t) { reportErr(t); }
    }

    private void reportErr(Throwable t) {
        try {
            StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
            if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.getClassName().contains("sesliyazi")) { sb.append(" @").append(e.getMethodName()).append(":").append(e.getLineNumber()); break; }
            }
            String m = sb.toString();
            getSharedPreferences("sesli", MODE_PRIVATE).edit().putString("lasterr", m).apply();
            setStatus("Hata: " + m);
        } catch (Throwable ignored) { }
    }
}
