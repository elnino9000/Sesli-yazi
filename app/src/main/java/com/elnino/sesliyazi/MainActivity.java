package com.elnino.sesliyazi;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Açılış ekranı: servisi açma, mikrofon izni ve deneme kutusu. */
public class MainActivity extends Activity {

    private TextView step1Status;
    private TextView step2Status;
    private TextView debug;

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

        TextView title = text("Sesli Yazı", 34, Color.WHITE);
        root.addView(title);

        TextView sub = text("Yazı kutusuna dokun, klavyenin üstünde çıkan mikrofona bas ve konuş.",
                18, Color.parseColor("#EBEBF5"));
        sub.setPadding(0, dp(8), 0, dp(20));
        root.addView(sub);

        // 1. adım
        step1Status = text("", 20, Color.WHITE);
        root.addView(card("1. Servisi aç", step1Status,
                "Erişilebilirlik ayarını aç", new int[]{0xFF0A84FF, 0xFF5E5CE6},
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                }));

        TextView note = text("Açılmıyor veya gri görünüyorsa: önce aşağıdaki butondan uygulama bilgisine gir, "
                + "sağ üstteki ⋮ menüsünden \"Kısıtlı ayarlara izin ver\"i seç. Sonra tekrar dene.",
                16, Color.parseColor("#EBEBF5"));
        note.setPadding(dp(4), dp(10), dp(4), dp(10));
        root.addView(note);

        root.addView(button("Uygulama bilgisini aç", new int[]{0xFF636366, 0xFF48484A},
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    }
                }));

        // 2. adım
        step2Status = text("", 20, Color.WHITE);
        LinearLayout c2 = card("2. Mikrofon izni", step2Status,
                "Mikrofon izni ver", new int[]{0xFF30D158, 0xFF0A9F6E},
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                    }
                });
        ((LinearLayout.LayoutParams) c2.getLayoutParams()).topMargin = dp(20);
        root.addView(c2);

        // Canlı durum (sorun olursa buraya bakılır)
        TextView dbgTitle = text("Durum", 22, Color.WHITE);
        dbgTitle.setPadding(0, dp(24), 0, dp(8));
        root.addView(dbgTitle);
        debug = text("", 17, Color.parseColor("#EBEBF5"));
        debug.setPadding(dp(16), dp(12), dp(16), dp(12));
        debug.setBackground(round("#1C1C1E", 18));
        root.addView(debug, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Deneme kutusu
        TextView testTitle = text("Dene", 22, Color.WHITE);
        testTitle.setPadding(0, dp(24), 0, dp(8));
        root.addView(testTitle);

        EditText test = new EditText(this);
        test.setHint("Buraya dokunup dene");
        test.setHintTextColor(Color.parseColor("#AEAEB2"));
        test.setTextColor(Color.WHITE);
        test.setTextSize(20);
        test.setTypeface(Typeface.DEFAULT_BOLD);
        test.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        test.setMinLines(3);
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
        ticker.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (debug != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("Servis: ").append(MicService.connected ? "çalışıyor" : "çalışmıyor")
                        .append("\nAlınan olay: ").append(MicService.events)
                        .append("\nKontrol: ").append(MicService.checks)
                        .append(" (son ").append(MicService.lastCheck).append(")");
                sb.append("\n\nOlay gelen uygulamalar (son görülen üstte):");
                java.util.ArrayList<String> ev = new java.util.ArrayList<>();
                synchronized (MicService.evPkgs) {
                    for (java.util.Map.Entry<String, Integer> e : MicService.evPkgs.entrySet()) {
                        ev.add(e.getKey().replace("com.", "") + ": " + e.getValue());
                    }
                }
                java.util.Collections.reverse(ev);
                for (String s : ev) sb.append("\n").append(s);
                sb.append("\n\nOlay ayrıntısı (başka uygulamalar):");
                synchronized (MicService.evLog) {
                    for (String s : MicService.evLog) sb.append("\n").append(s);
                }
                sb.append("\n\nSon durumlar (en yeni üstte):");
                synchronized (MicService.history) {
                    for (String s : MicService.history) sb.append("\n").append(s);
                }
                debug.setText(sb.toString());
                debug.setTextSize(14);
            }
            refresh();
            ui.postDelayed(this, 700);
        }
    };

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refresh();
    }

    private void refresh() {
        boolean svc = serviceOn();
        step1Status.setText(svc ? "✓ Açık" : "Kapalı");
        step1Status.setTextColor(svc ? Color.parseColor("#30D158") : Color.parseColor("#FF9F0A"));

        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        step2Status.setText(mic ? "✓ Verildi" : "Verilmedi");
        step2Status.setTextColor(mic ? Color.parseColor("#30D158") : Color.parseColor("#FF9F0A"));
    }

    private boolean serviceOn() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/");
    }

    // ---------------------------------------------------------- görünüm

    private LinearLayout card(String heading, TextView status, String buttonText,
                              int[] colors, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        c.setBackground(round("#1C1C1E", 22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c.setLayoutParams(lp);

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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
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
