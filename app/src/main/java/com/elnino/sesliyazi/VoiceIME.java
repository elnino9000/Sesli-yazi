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

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0B0F"));
        root.setPadding(dp(10), dp(8), dp(10), dp(8));

        status = label(18, Color.WHITE);
        status.setText("Konuşmak için mikrofona dokun");
        root.addView(status, wrapWidth());

        partial = label(20, Color.parseColor("#64D2FF"));
        partial.setMinLines(1);
        partial.setMaxLines(2);
        root.addView(partial, wrapWidth());

        micBtn = new FrameLayout(this);
        paintMic(false);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_mic);
        int p = dp(22);
        icon.setPadding(p, p, p, p);
        micBtn.addView(icon, new FrameLayout.LayoutParams(dp(88), dp(88)));
        micBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onMicTap(); }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(88), dp(88));
        mlp.gravity = Gravity.CENTER_HORIZONTAL;
        mlp.topMargin = dp(4);
        mlp.bottomMargin = dp(8);
        root.addView(micBtn, mlp);

        // noktalama satırı
        LinearLayout punct = new LinearLayout(this);
        punct.setOrientation(LinearLayout.HORIZONTAL);
        for (final String s : new String[]{".", ",", "?", "!"}) {
            punct.addView(key(s, 1f, new View.OnClickListener() {
                @Override public void onClick(View v) { punctuation(s); }
            }));
        }
        root.addView(punct, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        // ana tuşlar
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(key("🌐", 1f, new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopAll();
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showInputMethodPicker();
            }
        }));
        row.addView(key("Boşluk", 2.2f, new View.OnClickListener() {
            @Override public void onClick(View v) { commitRaw(" "); }
        }));
        TextView del = key("⌫", 1f, null);
        del.setOnTouchListener(new RepeatTouch());
        row.addView(del);
        row.addView(key("↵", 1f, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
            }
        }));
        row.addView(key("Bitti", 1.5f, new View.OnClickListener() {
            @Override public void onClick(View v) { goBack(); }
        }));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        rlp.topMargin = dp(4);
        root.addView(row, rlp);

        diag = label(12, Color.parseColor("#AEAEB2"));
        diag.setPadding(0, dp(4), 0, 0);
        root.addView(diag, wrapWidth());
        updateDiag();
        return root;
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

    private TextView key(String text, float weight, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#2C2C2E"));
        g.setCornerRadius(dp(14));
        t.setBackground(g);
        if (click != null) t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
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
        if (status != null) status.setText(s);
    }

    private void setPartial(String s) {
        if (partial != null) partial.setText(s);
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
            setStatus("Şifre kutusunda ses kullanılamaz. 🌐 ile klavyeni seç");
            setPartial("");
            return;
        }
        if (info.inputType == InputType.TYPE_NULL) {
            setStatus("Bu alana yazılamıyor");
            return;
        }
        if (listening || wanted) return;   // zaten dinliyoruz
        setStatus("Konuşmak için mikrofona dokun");
        setPartial("");
        h.postDelayed(autoStart, 500);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        h.removeCallbacks(autoStart);
        stopAll();
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        stopAll();
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
                setStatus("Konuşma tanıma çalışmadı. Alttaki hata kodunu bana yaz");
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
        ic.commitText(prefix + sp + " ", 1);
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
