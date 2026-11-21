package com.elnino.sesliyazi;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Açılış ekranı: klavyeyi etkinleştir, mikrofon izni ver, dene. */
public class MainActivity extends Activity {

    private TextView step1Status;
    private TextView step2Status;
    private TextView step3Status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#000000"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(56), pad, pad);
        scroll.addView(root);

        root.addView(text("Sesli Yazı", 34, Color.WHITE));

        TextView sub = text("Sesli Klavye: açılınca kendiliğinden dinler, söylediğini yazar. "
                + "Her uygulamada çalışır.", 18, Color.parseColor("#EBEBF5"));
        sub.setPadding(0, dp(8), 0, dp(20));
        root.addView(sub);

        step1Status = text("", 20, Color.WHITE);
        root.addView(card("1. Klavyeyi etkinleştir", step1Status,
                "Klavye ayarlarını aç", new int[]{0xFF0A84FF, 0xFF5E5CE6},
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                    }
                }));
        TextView n1 = text("Açılan listede \"Sesli Klavye\"nin düğmesini aç. "
                + "Uyarı çıkarsa Tamam de.", 16, Color.parseColor("#EBEBF5"));
        n1.setPadding(dp(4), dp(10), dp(4), dp(10));
        root.addView(n1);

        step2Status = text("", 20, Color.WHITE);
        root.addView(card("2. Mikrofon izni", step2Status,
                "Mikrofon izni ver", new int[]{0xFF30D158, 0xFF0A9F6E},
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                    }
                }));

        step3Status = text("", 20, Color.WHITE);
        LinearLayout c3 = card("3. Klavyeyi kullan", step3Status,
                "Klavye seç", new int[]{0xFFFF9F0A, 0xFFFF453A},
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        InputMethodManager imm =
                                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.showInputMethodPicker();
                    }
                });
        ((LinearLayout.LayoutParams) c3.getLayoutParams()).topMargin = dp(16);
        root.addView(c3);

        TextView n3 = text("Yazarken klavyedeki 🌐 tuşuna bas ve Sesli Klavye'yi seç. "
                + "Mikrofon kendiliğinden dinlemeye başlar. Bitince \"Bitti\" tuşu eski klavyene döndürür.",
                16, Color.parseColor("#EBEBF5"));
        n3.setPadding(dp(4), dp(10), dp(4), dp(10));
        root.addView(n3);

        TextView testTitle = text("Dene", 22, Color.WHITE);
        testTitle.setPadding(0, dp(16), 0, dp(8));
        root.addView(testTitle);

        EditText test = new EditText(this);
        test.setHint("Buraya dokunup dene");
        test.setHintTextColor(Color.parseColor("#AEAEB2"));
        test.setTextColor(Color.WHITE);
        test.setTextSize(20);
        test.setTypeface(Typeface.DEFAULT_BOLD);
        test.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        test.setMinLines(4);
        test.setGravity(Gravity.TOP | Gravity.START);
        test.setPadding(dp(16), dp(14), dp(16), dp(14));
        test.setBackground(round("#1C1C1E", 18));
        root.addView(test, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refresh();
    }

    private void refresh() {
        boolean enabled = imeEnabled();
        step1Status.setText(enabled ? "✓ Etkin" : "Etkin değil");
        step1Status.setTextColor(enabled ? Color.parseColor("#30D158") : Color.parseColor("#FF9F0A"));

        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        step2Status.setText(mic ? "✓ Verildi" : "Verilmedi");
        step2Status.setTextColor(mic ? Color.parseColor("#30D158") : Color.parseColor("#FF9F0A"));

        boolean current = imeSelected();
        step3Status.setText(current ? "✓ Şu an seçili" : "Seçili değil");
        step3Status.setTextColor(current ? Color.parseColor("#30D158") : Color.parseColor("#FF9F0A"));
    }

    private boolean imeEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
            if (i.getPackageName().equals(getPackageName())) return true;
        }
        return false;
    }

    private boolean imeSelected() {
        String cur = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        return cur != null && cur.startsWith(getPackageName() + "/");
    }

    // ---------------------------------------------------------- görünüm

    private LinearLayout card(String heading, TextView status, String buttonText,
                              int[] colors, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        c.setBackground(round("#1C1C1E", 22));
        c.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        c.addView(text(heading, 22, Color.WHITE));
        status.setPadding(0, dp(4), 0, dp(12));
        c.addView(status);
        c.addView(button(buttonText, colors, click));
        return c;
    }

    private View button(String label, int[] colors, View.OnClickListener click) {
        TextView b = text(label, 19, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
        g.setCornerRadius(dp(16));
        b.setBackground(g);
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private GradientDrawable round(String color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(color));
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
