package com.elnino.sesliyazi;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Yazı kutusuna dokunup klavye açılınca klavyenin üstünde mikrofon simgesi çıkarır.
 * Simgeye basınca konuşma dinlenir, söylenenler odaktaki yazı kutusuna yazılır.
 */
public class MicService extends AccessibilityService {

    private static final int[] BLUE = {0xFF0A84FF, 0xFF5E5CE6};
    private static final int[] RED = {0xFFFF453A, 0xFFFF9F0A};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateRun = new Runnable() {
        @Override public void run() { safeUpdate(); }
    };

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private FrameLayout bubble;
    private ObjectAnimator pulse;
    private boolean shown = false;

    private boolean listening = false;
    private SpeechRecognizer recognizer;
    private AccessibilityNodeInfo target;
    private String before = "";
    private String after = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        buildBubble();
        connected = true;
        toast("Sesli Yazı hazır");
        handler.removeCallbacks(pollRun);
        handler.postDelayed(pollRun, 1200);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        events++;
        try {
            if (event.getPackageName() != null) {
                lastPkg = event.getPackageName().toString();
                synchronized (evPkgs) {
                    Integer c = evPkgs.get(lastPkg);
                    evPkgs.put(lastPkg, c == null ? 1 : c + 1);
                    while (evPkgs.size() > 12) {
                        java.util.Iterator<String> it = evPkgs.keySet().iterator();
                        it.next();
                        it.remove();
                    }
                }
            }
            int t = event.getEventType();
            if (t == AccessibilityEvent.TYPE_VIEW_FOCUSED
                    || t == AccessibilityEvent.TYPE_VIEW_CLICKED
                    || t == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    || t == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                AccessibilityNodeInfo src = event.getSource();
                if (src != null && src.isEditable()) {
                    lastSource = src;
                    lastSrcAt = System.currentTimeMillis();
                    lastSrcPkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
                }
            }
            if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
                String p = event.getPackageName().toString();
                String sp = p.replace("com.", "");
                boolean skip = p.equals(getPackageName()) || p.contains("swiftkey")
                        || p.contains("inputmethod") || p.contains("keyboard")
                        || p.contains("systemui") || p.contains("screenshot");
                if (!skip) fgPkg = sp;
            }
        } catch (Exception ignored) { }
        // Klavye bazen gecikmeli açılır: birkaç kez tekrar kontrol et
        handler.removeCallbacks(updateRun);
        handler.postDelayed(updateRun, 120);
        handler.postDelayed(updateRun, 600);
        handler.postDelayed(updateRun, 1500);
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacks(updateRun);
        handler.removeCallbacks(pollRun);
        connected = false;
        endListening(true);
        hide();
        return super.onUnbind(intent);
    }

    // ---------------------------------------------------------------- simge

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildBubble() {
        int size = dp(60);
        bubble = new FrameLayout(this);
        paint(false);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_mic);
        int pad = dp(15);
        icon.setPadding(pad, pad, pad, pad);
        bubble.addView(icon, new FrameLayout.LayoutParams(size, size));

        bubble.setOnClickListener(v -> onBubbleTap());

        lp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
    }

    private void paint(boolean active) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, active ? RED : BLUE);
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(2), 0x55FFFFFF);
        bubble.setBackground(g);
    }

    private void show(int keyboardTop) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = dp(60);
        int margin = dp(12);
        int x = dm.widthPixels - size - margin;
        int y = keyboardTop - size - margin;
        if (y < 0) y = margin;
        if (!shown) {
            lp.x = x;
            lp.y = y;
            wm.addView(bubble, lp);
            shown = true;
        } else if (lp.x != x || lp.y != y) {
            lp.x = x;
            lp.y = y;
            wm.updateViewLayout(bubble, lp);
        }
    }

    private void hide() {
        if (shown) {
            try { wm.removeView(bubble); } catch (Exception ignored) { }
            shown = false;
        }
    }

    // ------------------------------------------------- klavye / kutu takibi

    private static final int KB_UNKNOWN = -2;
    private boolean errorShown = false;
    private boolean imeSeen = false;
    private AccessibilityNodeInfo lastSource;
    private long lastSrcAt = 0;
    private String lastSrcPkg = "";
    private String fgPkg = "";
    private String lastPkg = "";
    private String lastToast = "";
    private long lastToastAt = 0;

    private String windowsSummary() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return "yok";
            StringBuilder sb = new StringBuilder();
            for (AccessibilityWindowInfo w : ws) {
                int t = w.getType();
                sb.append(t == AccessibilityWindowInfo.TYPE_APPLICATION ? "U"
                        : t == AccessibilityWindowInfo.TYPE_INPUT_METHOD ? "K"
                        : t == AccessibilityWindowInfo.TYPE_SYSTEM ? "S" : "?");
            }
            return ws.size() + " (" + sb + ")";
        } catch (Exception e) {
            return "hata";
        }
    }

    // Açılış ekranındaki "Durum" satırı için
    static volatile boolean connected = false;
    static volatile int events = 0;
    static volatile String info = "";
    static final java.util.LinkedHashMap<String, Integer> evPkgs =
            new java.util.LinkedHashMap<String, Integer>(16, 0.75f, true);
    static volatile int checks = 0;
    static volatile String lastCheck = "-";
    static final java.util.List<String> history =
            java.util.Collections.synchronizedList(new ArrayList<String>());

    private final Runnable pollRun = new Runnable() {
        @Override public void run() {
            safeUpdate();
            handler.postDelayed(this, 1200);
        }
    };

    /** Klavyenin ekrandaki üst kenarı. -1: klavye kapalı, -2: telefon bilgi vermiyor. */
    private int keyboardTop() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) return KB_UNKNOWN;
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    if (r.height() > 0) return r.top;
                }
            }
            return -1;
        } catch (Exception e) {
            return KB_UNKNOWN;
        }
    }

    private AccessibilityNodeInfo findInput() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (n != null) return n;
            }
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    AccessibilityNodeInfo n = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (n != null) return n;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void safeUpdate() {
        try {
            update();
        } catch (Exception e) {
            info = "HATA: " + e;
            String err = "HATA: " + e;
            if (history.isEmpty() || !history.get(0).endsWith(err)) {
                history.add(0, "--  " + err);
                while (history.size() > 30) history.remove(history.size() - 1);
            }
            if (!errorShown) {
                errorShown = true;
                toast("Sesli Yazı hata: " + e);
            }
        }
    }

    /** Odaktaki yazı kutusunu ağaçta tarayarak bulur (findFocus işe yaramazsa). */
    private AccessibilityNodeInfo scanForEditable() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            List<AccessibilityNodeInfo> roots = new ArrayList<>();
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) roots.add(active);
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r != null) roots.add(r);
                    }
                }
            }
            int seen = 0;
            for (AccessibilityNodeInfo root : roots) {
                ArrayList<AccessibilityNodeInfo> queue = new ArrayList<>();
                queue.add(root);
                for (int i = 0; i < queue.size() && seen < 600; i++) {
                    AccessibilityNodeInfo n = queue.get(i);
                    seen++;
                    if (n.isEditable() && (n.isFocused() || n.isAccessibilityFocused())) return n;
                    for (int c = 0; c < n.getChildCount(); c++) {
                        AccessibilityNodeInfo ch = n.getChild(c);
                        if (ch != null) queue.add(ch);
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private String activePackage() {
        try {
            AccessibilityNodeInfo ar = getRootInActiveWindow();
            return ar == null ? "boş" : String.valueOf(ar.getPackageName()).replace("com.", "");
        } catch (Exception e) {
            return "hata";
        }
    }

    private void update() {
        checks++;
        lastCheck = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date());

        int kb = keyboardTop();
        if (kb >= 0) imeSeen = true;
        String activePkg = activePackage();

        // Hafif arama: odaktaki kutuyu sistemden iste. Ağır taramayı sadece klavye bilgisi yoksa yap.
        AccessibilityNodeInfo f = findInput();
        if (f == null && lastSource != null) {
            try {
                if (lastSource.refresh() && lastSource.isFocused()) f = lastSource;
            } catch (Exception ignored) { }
        }
        if (f == null && kb < 0 && !imeSeen) f = scanForEditable();
        boolean editable = f != null && f.isEditable();

        // Olaydan gelen kutu: pencere sorguları işe yaramayan uygulamalar için (örn. WhatsApp Business)
        String srcShort = lastSrcPkg.replace("com.", "");
        boolean srcOk = false;
        if (lastSource != null && System.currentTimeMillis() - lastSrcAt < 45000) {
            try {
                srcOk = lastSource.refresh() && lastSource.isEditable() && lastSource.isFocused()
                        && !lastSource.isPassword()
                        && (fgPkg.isEmpty() || fgPkg.equals(srcShort));
            } catch (Exception ignored) { }
        }
        boolean queriesWork = activePkg.equals(srcShort);
        // Pencere sorguları güvenilirse ve klavye kapalıysa olay yolu simgeyi tutmasın
        if (srcOk && queriesWork && imeSeen && kb < 0) srcOk = false;

        // Teşhis kaydı (uygulamadaki Durum kutusunda görünür)
        boolean noise = activePkg.contains("screenshot") || activePkg.contains("systemui");
        String brief = activePkg
                + " | Klavye " + (kb >= 0 ? "VAR" : kb == -1 ? "YOK" : "?")
                + " | Kutu " + (f == null ? "YOK" : editable ? "VAR" : "yazılamaz")
                + " | Olay " + (srcOk ? "VAR" : "YOK") + " " + srcShort + "/" + fgPkg
                + " | " + windowsSummary()
                + " | simge " + (shown ? "açık" : "kapalı");
        if (!noise && !brief.equals(lastToast)) {
            lastToast = brief;
            history.add(0, lastCheck + "  " + brief);
            while (history.size() > 40) history.remove(history.size() - 1);
        }

        if (f != null && f.isPassword()) {
            endListening(true);
            hide();
            return;
        }

        // Klavye görülüyorsa simge çıkar. Telefon klavyeyi hiç bildirmiyorsa, odakta yazı kutusu yeter.
        boolean visible = kb >= 0 || (!imeSeen && editable) || srcOk;
        if (!visible) {
            endListening(true);
            hide();
            return;
        }
        int top = kb >= 0 ? kb : getResources().getDisplayMetrics().heightPixels * 55 / 100;
        show(top);
    }

    // ---------------------------------------------------------- dinleme

    private void onBubbleTap() {
        if (listening) {
            if (recognizer != null) {
                try { recognizer.stopListening(); } catch (Exception ignored) { }
            }
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            toast("Önce Sesli Yazı uygulamasını aç ve mikrofon iznini ver");
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Telefonda konuşma tanıma servisi yok (Google uygulaması gerekli)");
            return;
        }
        startListening();
    }

    private void startListening() {
        AccessibilityNodeInfo f = findInput();
        if (f == null) f = scanForEditable();
        if (f == null && lastSource != null) {
            try {
                if (lastSource.refresh()) f = lastSource;
            } catch (Exception ignored) { }
        }
        if (f == null) {
            toast("Yazı kutusu bulunamadı. Kutuya bir kez daha dokunup tekrar dene");
            return;
        }
        target = f;

        CharSequence t = f.getText();
        String cur = t == null ? "" : t.toString();
        if (Build.VERSION.SDK_INT >= 26 && f.isShowingHintText()) cur = "";
        int s = f.getTextSelectionStart();
        int e = f.getTextSelectionEnd();
        if (s < 0 || e < 0) { s = cur.length(); e = cur.length(); }
        if (s > e) { int x = s; s = e; e = x; }
        s = Math.min(s, cur.length());
        e = Math.min(e, cur.length());
        before = cur.substring(0, s);
        after = cur.substring(e);

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new Listener());

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
        paint(true);
        startPulse();
        try {
            recognizer.startListening(intent);
        } catch (Exception ex) {
            endListening(true);
            toast("Dinleme başlatılamadı");
        }
    }

    private void endListening(boolean cancel) {
        if (recognizer != null) {
            try {
                if (cancel) recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) { }
            recognizer = null;
        }
        listening = false;
        stopPulse();
        if (bubble != null) paint(false);
    }

    private void startPulse() {
        stopPulse();
        pulse = ObjectAnimator.ofPropertyValuesHolder(bubble,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.18f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.18f));
        pulse.setDuration(550);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (bubble != null) {
            bubble.setScaleX(1f);
            bubble.setScaleY(1f);
        }
    }

    private class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partialResults) {
            String s = first(partialResults);
            if (s != null) apply(s, false);
        }

        @Override
        public void onResults(Bundle results) {
            String s = first(results);
            if (s != null) apply(s, true);
            endListening(false);
            handler.postDelayed(updateRun, 150);
        }

        @Override
        public void onError(int error) {
            endListening(true);
            switch (error) {
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                case SpeechRecognizer.ERROR_NO_MATCH:
                    toast("Ses duyulmadı, tekrar dene");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    toast("İnternet bağlantısı gerekli");
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    toast("Mikrofon izni yok. Uygulamayı açıp izin ver");
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    toast("Konuşma tanıma meşgul, bir daha dene");
                    break;
                default:
                    break;
            }
            handler.postDelayed(updateRun, 150);
        }
    }

    private String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return null;
        return list.get(0);
    }

    // ------------------------------------------------------ metni yazma

    private void apply(String spoken, boolean last) {
        String sp = spoken.trim();
        if (sp.isEmpty() || target == null) return;

        String bt = before.trim();
        if (bt.isEmpty() || bt.endsWith(".") || bt.endsWith("?") || bt.endsWith("!")) {
            sp = sp.substring(0, 1).toUpperCase(new Locale("tr", "TR")) + sp.substring(1);
        }
        String sepBefore = (!before.isEmpty()
                && !Character.isWhitespace(before.charAt(before.length() - 1))) ? " " : "";
        String sepAfter = (!after.isEmpty()
                && !Character.isWhitespace(after.charAt(0))) ? " " : "";
        String head = before + sepBefore + sp;
        String full = head + sepAfter + after;

        try {
            target.refresh();
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, full);
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (ok) {
                Bundle sel = new Bundle();
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, head.length());
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, head.length());
                target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
            } else if (last) {
                // Bazı kutular doğrudan yazmaya izin vermez: panoya koyup yapıştır
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Sesli Yazı", sepBefore + sp));
                target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            }
        } catch (Exception ignored) { }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
