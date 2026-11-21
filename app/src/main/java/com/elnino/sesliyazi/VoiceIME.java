package com.elnino.sesliyazi;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Sesli Klavye: açılınca otomatik dinler, söylenenleri odaktaki yazı kutusuna yazar.
 * Klavye olduğu için her uygulamada (WhatsApp, Notlar, tarayıcı...) çalışır.
 */
public class VoiceIME extends InputMethodService {

    private static final int[] BLUE = {0xFF0A84FF, 0xFF5E5CE6};
    private static final int[] RED = {0xFFFF453A, 0xFFFF9F0A};

    private final Handler h = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private boolean wanted = false;     // kullanıcı dinlenmesini istiyor (sürekli mod)
    private boolean listening = false;
    private boolean password = false;
    private boolean busyRetried = false;

    private String prefix = "";
    private boolean capNext = true;

    private TextView status;
    private FrameLayout micBtn;
    private ObjectAnimator pulse;

    private final Runnable autoStart = new Runnable() {
        @Override public void run() {
            if (!listening && !password) begin();
        }
    };

    // ------------------------------------------------------------ görünüm

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0B0F"));
        root.setPadding(dp(12), dp(10), dp(12), dp(12));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setGravity(Gravity.CENTER);
        status.setText("Konuşmak için mikrofona dokun");
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        micBtn = new FrameLayout(this);
        paintMic(false);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_mic);
        int p = dp(26);
        icon.setPadding(p, p, p, p);
        micBtn.addView(icon, new FrameLayout.LayoutParams(dp(104), dp(104)));
        micBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onMicTap(); }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(104), dp(104));
        mlp.gravity = Gravity.CENTER_HORIZONTAL;
        mlp.topMargin = dp(10);
        mlp.bottomMargin = dp(12);
        root.addView(micBtn, mlp);

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
            @Override public void onClick(View v) { commit(" "); }
        }));
        TextView del = key("⌫", 1f, null);
        del.setOnTouchListener(new RepeatTouch());
        row.addView(del);
        row.addView(key("↵", 1f, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
            }
        }));
        row.addView(key("Bitti", 1.4f, new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopAll();
                switchBack();
            }
        }));
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return root;
    }

    private TextView key(String label, float weight, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#2C2C2E"));
        g.setCornerRadius(dp(14));
        t.setBackground(g);
        if (click != null) t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void paintMic(boolean active) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, active ? RED : BLUE);
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(3), 0x66FFFFFF);
        micBtn.setBackground(g);
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
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

    // ------------------------------------------------------- klavye yaşamı

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int var = info.inputType & InputType.TYPE_MASK_VARIATION;
        password = (cls == InputType.TYPE_CLASS_TEXT
                && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (cls == InputType.TYPE_CLASS_NUMBER
                && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        h.removeCallbacks(autoStart);
        if (password) {
            setStatus("Şifre kutusunda ses kullanılamaz. 🌐 ile klavyeni seç");
            return;
        }
        setStatus("Konuşmak için mikrofona dokun");
        h.postDelayed(autoStart, 400);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        h.removeCallbacks(autoStart);
        stopAll();
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    private void switchBack() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean ok;
        if (Build.VERSION.SDK_INT >= 28) {
            ok = switchToPreviousInputMethod();
        } else {
            IBinder token = getWindow().getWindow().getAttributes().token;
            ok = imm.switchToLastInputMethod(token);
        }
        if (!ok) imm.showInputMethodPicker();
    }

    // ------------------------------------------------------------- dinleme

    private void onMicTap() {
        if (password) return;
        if (listening) {
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
            setStatus("Mikrofon izni yok. Sesli Yazı uygulamasını açıp izin ver");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Konuşma tanıma yok (Google uygulaması gerekli)");
            return;
        }
        wanted = true;
        busyRetried = false;
        startRecognizer();
    }

    private void startRecognizer() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            setStatus("Yazı kutusu bulunamadı");
            wanted = false;
            return;
        }
        // Yazının başında mıyız, cümle sonunda mıyız: baş harf ve boşluk kararı
        CharSequence before = ic.getTextBeforeCursor(3, 0);
        String b = before == null ? "" : before.toString();
        prefix = (!b.isEmpty() && !Character.isWhitespace(b.charAt(b.length() - 1))) ? " " : "";
        String t = b.trim();
        capNext = t.isEmpty() || t.endsWith(".") || t.endsWith("?") || t.endsWith("!");

        destroyRecognizer();
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
        paintMic(true);
        startPulse();
        setStatus("Dinliyorum… konuş");
        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            stopAll();
            setStatus("Dinleme başlatılamadı");
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void stopAll() {
        wanted = false;
        listening = false;
        destroyRecognizer();
        stopPulse();
        if (micBtn != null) paintMic(false);
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
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partialResults) {
            String s = first(partialResults);
            if (s != null) compose(s);
        }

        @Override
        public void onResults(Bundle results) {
            String s = first(results);
            InputConnection ic = getCurrentInputConnection();
            if (s != null && ic != null) {
                compose(s);
                ic.finishComposingText();
                ic.commitText(" ", 1);
            }
            listening = false;
            stopPulse();
            paintMic(false);
            destroyRecognizer();
            if (wanted) {
                // Sürekli yazım: kısa bekleyip tekrar dinle
                h.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (wanted && !listening) startRecognizer();
                    }
                }, 250);
            } else {
                setStatus("Konuşmak için mikrofona dokun");
            }
        }

        @Override
        public void onError(int error) {
            listening = false;
            stopPulse();
            paintMic(false);
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
            destroyRecognizer();

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
            switch (error) {
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                case SpeechRecognizer.ERROR_NO_MATCH:
                    setStatus("Ses duyulmadı. Mikrofona dokunup tekrar konuş");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    setStatus("İnternet bağlantısı gerekli");
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    setStatus("Mikrofon izni yok. Sesli Yazı uygulamasını açıp izin ver");
                    break;
                default:
                    setStatus("Bir sorun oldu. Mikrofona dokunup tekrar dene");
                    break;
            }
        }
    }

    private String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return null;
        return list.get(0);
    }

    /** Söyleneni yazı kutusuna geçici (altı çizili) yazı olarak koyar. */
    private void compose(String spoken) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String sp = spoken.trim();
        if (sp.isEmpty()) return;
        if (capNext) {
            sp = sp.substring(0, 1).toUpperCase(new Locale("tr", "TR")) + sp.substring(1);
        }
        ic.setComposingText(prefix + sp, 1);
    }

    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
    }
}
