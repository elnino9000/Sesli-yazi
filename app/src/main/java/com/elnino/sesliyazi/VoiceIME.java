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
    private final TextView[] sugg = new TextView[3];
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
        {"-","/",":",";","(",")","₺","&","@","\""},
        {".",",","?","!","'","%","+","="}};

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

    private final java.util.HashMap<String, Integer> words = new java.util.HashMap<String, Integer>();  // sözlük (taban puan)
    private final java.util.HashMap<String, Integer> own = new java.util.HashMap<String, Integer>();    // senin yazdıkların
    private final java.util.HashMap<String, Integer> pairs = new java.util.HashMap<String, Integer>();  // "önceki>sonraki"
    private final StringBuilder cur = new StringBuilder();
    private String prev = "";
    private int learnCount = 0;
    private long lastOwn = 0;
    private final Locale TR = new Locale("tr", "TR");

    private boolean isWord(String w) {
        if (w == null || w.length() < 2 || w.length() > 30) return false;
        for (int i = 0; i < w.length(); i++) if (!Character.isLetter(w.charAt(i))) return false;
        return true;
    }

    private void loadWords() {
        words.clear();
        own.clear();
        pairs.clear();
        for (String w : BUILTIN.split(" ")) {
            if (w.length() > 0) words.put(w, 3);
        }
        // Derleme sırasında indirilen büyük Türkçe kelime listesi (varsa)
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    getAssets().open("words_tr.txt"), "UTF-8"));
            String line;
            int rank = 0;
            while ((line = r.readLine()) != null) {
                String w = line.trim();
                if (!isWord(w)) continue;
                rank++;
                int base = rank < 400 ? 3 : (rank < 4000 ? 2 : 1);
                Integer old = words.get(w);
                if (old == null || old < base) words.put(w, base);
            }
            r.close();
        } catch (Exception ignored) { }

        SharedPreferences sp = getSharedPreferences("sesli", MODE_PRIVATE);
        for (String e : sp.getString("own", "").split(",")) {
            int i = e.lastIndexOf(':');
            if (i <= 0) continue;
            try {
                String w = e.substring(0, i);
                own.put(w, Integer.parseInt(e.substring(i + 1)));
                if (!words.containsKey(w)) words.put(w, 0);
            } catch (Exception ignored) { }
        }
        for (String e : sp.getString("pairs", "").split(",")) {
            int i = e.lastIndexOf(':');
            if (i <= 0) continue;
            try {
                pairs.put(e.substring(0, i), Integer.parseInt(e.substring(i + 1)));
            } catch (Exception ignored) { }
        }
    }

    private int score(String w) {
        Integer b = words.get(w);
        Integer o = own.get(w);
        return (b == null ? 0 : b) + (o == null ? 0 : o * 4);
    }

    /** Bir kelimeyi öğrenir; önceki kelimeyle ikilisini de kaydeder. */
    private void learn(String w) {
        if (w == null) return;
        w = w.toLowerCase(TR);
        if (!isWord(w)) return;
        Integer c = own.get(w);
        own.put(w, (c == null ? 0 : c) + 1);
        if (!words.containsKey(w)) words.put(w, 0);
        if (prev.length() > 0) {
            String k = prev + ">" + w;
            Integer pc = pairs.get(k);
            pairs.put(k, (pc == null ? 0 : pc) + 1);
        }
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

    private void saveWords() {
        if (own.size() > 6000) {
            java.util.Iterator<java.util.Map.Entry<String, Integer>> it = own.entrySet().iterator();
            while (it.hasNext() && own.size() > 4500) if (it.next().getValue() <= 1) it.remove();
        }
        if (pairs.size() > 8000) {
            java.util.Iterator<java.util.Map.Entry<String, Integer>> it = pairs.entrySet().iterator();
            while (it.hasNext() && pairs.size() > 6000) if (it.next().getValue() <= 1) it.remove();
        }
        StringBuilder a = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : own.entrySet()) {
            if (a.length() > 0) a.append(',');
            a.append(e.getKey()).append(':').append(e.getValue());
        }
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : pairs.entrySet()) {
            if (b.length() > 0) b.append(',');
            b.append(e.getKey()).append(':').append(e.getValue());
        }
        getSharedPreferences("sesli", MODE_PRIVATE).edit()
                .putString("own", a.toString()).putString("pairs", b.toString()).apply();
    }

    /** İmleçten önceki yazıdan o anki kelimeyi ve önceki kelimeyi okur. */
    private void syncFromText() {
        InputConnection ic = getCurrentInputConnection();
        cur.setLength(0);
        prev = "";
        if (ic == null) return;
        CharSequence cs = ic.getTextBeforeCursor(60, 0);
        if (cs == null) return;
        String b = cs.toString();
        int end = b.length();
        int i = end;
        while (i > 0 && Character.isLetter(b.charAt(i - 1))) i--;
        cur.append(b.substring(i, end));
        // önceki kelime: arada sadece boşluk varsa
        int j = i;
        while (j > 0 && (b.charAt(j - 1) == ' ' || b.charAt(j - 1) == ' ')) j--;
        if (j < i || cur.length() == 0) {
            int k = j;
            while (k > 0 && Character.isLetter(b.charAt(k - 1))) k--;
            if (k < j && j < b.length() + 1) prev = b.substring(k, j).toLowerCase(TR);
        }
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
        // imleç dışarıdan oynadı (kullanıcı başka yere dokundu)
        syncFromText();
        updateSuggestions();
        autoShift();
    }

    private void updateSuggestions() {
        if (sugg[0] == null) return;
        String[] out = new String[]{"", "", ""};
        int[] sc = new int[]{-1, -1, -1};
        if (cur.length() == 0) {
            // sonraki kelime tahmini
            if (prev.length() > 0) {
                String pre = prev + ">";
                for (java.util.Map.Entry<String, Integer> e : pairs.entrySet()) {
                    String k = e.getKey();
                    if (!k.startsWith(pre)) continue;
                    rank(out, sc, k.substring(pre.length()), e.getValue() * 100 + score(k.substring(pre.length())));
                }
            }
            // boşlukları sık yazdığın kelimelerle doldur
            if (out[2].length() == 0) {
                for (java.util.Map.Entry<String, Integer> e : own.entrySet()) {
                    String w = e.getKey();
                    if (w.equals(out[0]) || w.equals(out[1]) || w.equals(out[2])) continue;
                    rank(out, sc, w, e.getValue());
                }
            }
            String[] def = new String[]{"Merhaba", "Tamam", "Teşekkürler"};
            for (int i = 0; i < 3; i++) {
                if (out[i].length() == 0) {
                    for (String d : def) {
                        if (!d.equalsIgnoreCase(out[0]) && !d.equalsIgnoreCase(out[1])
                                && !d.equalsIgnoreCase(out[2])) { out[i] = d; break; }
                    }
                }
            }
            boolean sentenceStart = shift == 1;
            if (sentenceStart) {
                for (int i = 0; i < 3; i++)
                    if (out[i].length() > 0) out[i] = out[i].substring(0, 1).toUpperCase(TR) + out[i].substring(1);
            }
        } else {
            String p = cur.toString().toLowerCase(TR);
            for (java.util.Map.Entry<String, Integer> e : words.entrySet()) {
                String w = e.getKey();
                if (w.length() <= p.length() || !w.startsWith(p)) continue;
                int s = score(w) * 100 - w.length();
                if (prev.length() > 0) {
                    Integer pc = pairs.get(prev + ">" + w);
                    if (pc != null) s += pc * 1000;
                }
                rank(out, sc, w, s);
            }
            boolean capFirst = Character.isUpperCase(cur.charAt(0));
            for (int i = 0; i < 3; i++) {
                if (out[i].length() > 0 && capFirst)
                    out[i] = out[i].substring(0, 1).toUpperCase(TR) + out[i].substring(1);
            }
            if (out[0].length() == 0) out[0] = cur.toString();
        }
        for (int i = 0; i < 3; i++) sugg[i].setText(out[i]);
    }

    private void rank(String[] out, int[] sc, String w, int s) {
        for (int i = 0; i < 3; i++) if (out[i].equals(w)) return;
        for (int i = 0; i < 3; i++) {
            if (s > sc[i]) {
                for (int j = 2; j > i; j--) { sc[j] = sc[j - 1]; out[j] = out[j - 1]; }
                sc[i] = s;
                out[i] = w;
                return;
            }
        }
    }

    private void pickSuggestion(int i) {
        String w = sugg[i].getText().toString();
        if (w.length() == 0) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        syncFromText();   // yanlış silmemek için gerçek durumu oku
        markOwn();
        if (cur.length() > 0) ic.deleteSurroundingText(cur.length(), 0);
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
        loadWords();
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
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setTextColor(Color.WHITE);
            t.setTextSize(19);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) pickSuggestion(idx);
                    return true;
                }
            });
            sugg[i] = t;
            suggRow.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        bar.addView(suggRow, new FrameLayout.LayoutParams(
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
                if (e.getAction() == MotionEvent.ACTION_DOWN) onMicTap();
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
        final Runnable rep = new Runnable() {
            @Override public void run() {
                action.run();
                h.postDelayed(this, 55);
            }
        };
        t.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        g.setColor(KEY_DOWN);
                        action.run();
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
        letterViews.clear();
        letterBase.clear();
        shiftKey = null;
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
        b.addView(mkKey("🌐", 1.1f, true, new Runnable() {
            @Override public void run() {
                stopAll();
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showInputMethodPicker();
            }
        }, false));
        b.addView(mkKey(",", 1f, false, new Runnable() {
            @Override public void run() { typeKey(","); }
        }, false));
        b.addView(mkKey("Türkçe", 4f, false, new Runnable() {
            @Override public void run() { doSpace(); }
        }, false));
        b.addView(mkKey(".", 1f, false, new Runnable() {
            @Override public void run() { typeKey("."); }
        }, false));
        b.addView(mkKey("↵", 1.4f, true, new Runnable() {
            @Override public void run() {
                markOwn();
                learn(cur.toString());
                cur.setLength(0);
                prev = "";
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

    private void typeKey(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        markOwn();
        ic.commitText(s, 1);
        leaveStatus();
        boolean letter = Character.isLetter(s.charAt(0));
        if (letter) {
            cur.append(s);
            if (shift == 1) { shift = 0; refreshLabels(); }
        } else {
            learn(cur.toString());
            cur.setLength(0);
            if (s.equals(".") || s.equals("?") || s.equals("!")) prev = "";
            if ((s.equals(".") || s.equals("?") || s.equals("!")) && page == 0 && shift != 2) {
                shift = 1;
                refreshLabels();
            }
        }
        updateSuggestions();
    }

    private void doSpace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        markOwn();
        ic.commitText(" ", 1);
        leaveStatus();
        learn(cur.toString());
        cur.setLength(0);
        updateSuggestions();
    }

    private void doDelete() {
        markOwn();
        if (cur.length() > 0) cur.setLength(cur.length() - 1);
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        updateSuggestions();
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

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        h.removeCallbacks(autoStart);

        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int var = info.inputType & InputType.TYPE_MASK_VARIATION;
        password = (cls == InputType.TYPE_CLASS_TEXT
                && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (cls == InputType.TYPE_CLASS_NUMBER
                && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD);

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
        if (listening) {
            // Durdur: o ana kadar söylenenler yazılır
            wanted = false;
            if (recognizer != null) {
                try { recognizer.stopListening(); } catch (Exception ignored) { }
            }
            setStatus("Yazılıyor…");
        } else {
            begin();
        }
    }

    private void begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            lastErr = "mikrofon izni yok";
            updateDiag();
            setStatus("Mikrofon izni yok. Sesli Yazı uygulamasını açıp izin ver");
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
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);

        listening = true;
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
        wanted = false;
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
            if (s != null) setPartial(s);
        }

        @Override
        public void onResults(Bundle results) {
            if (mySession != session) return;
            String s = first(results);
            if (s != null) {
                commitFinal(s);
                rememberGood();
            }
            lastErr = "-";
            updateDiag();
            setPartial("");
            listening = false;
            stopPulse();
            paintMic(false);
            destroyRecognizer();
            if (wanted) {
                // Sürekli yazım: kısa bekleyip tekrar dinle
                h.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (wanted && !listening && mySession == session) startRecognizer();
                    }
                }, 250);
            } else {
                setStatus("Konuşmak için mikrofona dokun");
            }
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
        lastErr = errName(error);
        listening = false;
        stopPulse();
        paintMic(false);
        destroyRecognizer();
        setPartial("");

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
                setStatus("Mikrofon izni yok. Sesli Yazı uygulamasını açıp izin ver");
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
    private void commitFinal(String spoken) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            setStatus("Yazı kutusu bulunamadı");
            return;
        }
        String sp = spoken.trim();
        if (sp.isEmpty()) return;

        CharSequence before = ic.getTextBeforeCursor(3, 0);
        String b = before == null ? "" : before.toString();
        String t = b.trim();
        boolean cap = t.isEmpty() || t.endsWith(".") || t.endsWith("?") || t.endsWith("!");
        if (cap) {
            sp = sp.substring(0, 1).toUpperCase(new Locale("tr", "TR")) + sp.substring(1);
        }
        String prefix = (!b.isEmpty() && !Character.isWhitespace(b.charAt(b.length() - 1))) ? " " : "";
        markOwn();
        ic.commitText(prefix + sp + " ", 1);
        cur.setLength(0);
        learnText(sp);
        updateSuggestions();
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
}
