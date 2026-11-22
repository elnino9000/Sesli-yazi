package com.elnino.sesliyazi;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** WhatsApp sohbet dosyasını "Paylaş" ile alır ve yazışmalarından öğrenir. */
public class ShareActivity extends Activity {

    private TextView info;
    private TextView btn;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("Yazışmalardan öğreniyor");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        info = new TextView(this);
        info.setText("Okunuyor, birkaç saniye sürebilir…");
        info.setTextColor(Color.parseColor("#64D2FF"));
        info.setTextSize(19);
        info.setTypeface(Typeface.DEFAULT_BOLD);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(20), 0, dp(20));
        root.addView(info);

        btn = new TextView(this);
        btn.setText("Tamam");
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(20);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(30), dp(14), dp(30), dp(14));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF0A84FF, 0xFF5E5CE6});
        g.setCornerRadius(dp(16));
        btn.setBackground(g);
        btn.setVisibility(View.GONE);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(btn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        final Intent in = getIntent();
        new Thread(new Runnable() {
            @Override public void run() { work(in); }
        }).start();
    }

    private void work(Intent in) {
        String result;
        try {
            Uri uri = null;
            if (in.getData() != null) uri = in.getData();
            if (uri == null) uri = (Uri) in.getParcelableExtra(Intent.EXTRA_STREAM);
            InputStream is = null;
            if (uri != null) {
                is = getContentResolver().openInputStream(uri);
            } else {
                CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (t != null) is = new ByteArrayInputStream(t.toString().getBytes("UTF-8"));
            }
            if (is == null) {
                result = "Dosya okunamadı. WhatsApp'ta sohbeti \"Medyasız\" dışa aktarıp tekrar dene.";
            } else {
                result = Importer.learn(this, is);
            }
        } catch (Throwable e) {
            result = "Öğrenme olmadı: " + e.getClass().getSimpleName();
        }
        final String r = result;
        h.post(new Runnable() {
            @Override public void run() {
                info.setText(r);
                btn.setVisibility(View.VISIBLE);
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
