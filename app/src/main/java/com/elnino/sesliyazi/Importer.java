package com.elnino.sesliyazi;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * WhatsApp "Sohbeti dışa aktar" dosyasından (veya düz yazıdan) kelime ve kelime-ikilisi öğrenir.
 * Öğrenilenler klavyenin kişisel hafızasına (SharedPreferences "sesli") eklenir.
 */
public final class Importer {

    private static final Locale TR = new Locale("tr", "TR");

    // 21.11.2026 14:03 - Ad: mesaj   |   [21.11.2026 14:03:11] Ad: mesaj
    private static final Pattern LINE = Pattern.compile(
            "^\\[?\\d{1,2}[./]\\d{1,2}[./]\\d{2,4},?\\s+\\d{1,2}[:.]\\d{2}(?:[:.]\\d{2})?\\]?\\s*(?:[-–]\\s*)?([^:]{1,60}):\\s(.*)$");

    private Importer() { }

    /** Sonuç özeti döndürür. */
    public static String learn(Context ctx, InputStream raw) throws IOException {
        String text = readAll(raw);
        List<String> messages = extractMessages(text);
        HashSet<String> known = loadKnown(ctx);

        // 1. geçiş: kelime sayıları
        HashMap<String, Integer> cnt = new HashMap<String, Integer>();
        List<List<String>> sentences = new ArrayList<List<String>>();
        for (String m : messages) {
            for (List<String> s : sentencesOf(m)) {
                sentences.add(s);
                for (String w : s) {
                    Integer c = cnt.get(w);
                    cnt.put(w, c == null ? 1 : c + 1);
                }
            }
        }
        // kabul: sözlükte var veya en az 3 kez yazılmış (yazım hatası değil, örn. isim)
        HashSet<String> ok = new HashSet<String>();
        for (Map.Entry<String, Integer> e : cnt.entrySet()) {
            if (known.contains(e.getKey()) || e.getValue() >= 3) ok.add(e.getKey());
        }

        // 2. geçiş: tekli, ikili, üçlü
        HashMap<String, Integer> own = new HashMap<String, Integer>();
        HashMap<String, Integer> pairs = new HashMap<String, Integer>();
        HashMap<String, Integer> triples = new HashMap<String, Integer>();
        for (List<String> s : sentences) {
            String prev = "", prev2 = "";
            for (String w : s) {
                if (!ok.contains(w)) continue;      // hatalı kelime bağlamı bozmasın, atla
                inc(own, w);
                if (prev.length() > 0) {
                    inc(pairs, prev + ">" + w);
                    inc(triples, (prev2.length() > 0 ? prev2 : "^") + " " + prev + ">" + w);
                } else {
                    inc(pairs, "^>" + w);
                }
                prev2 = prev;
                prev = w;
            }
        }

        merge(ctx, own, pairs, triples);
        return messages.size() + " mesaj okundu, " + own.size() + " kelime ve "
                + pairs.size() + " kelime ikilisi öğrenildi.";
    }

    private static void inc(HashMap<String, Integer> m, String k) {
        Integer c = m.get(k);
        m.put(k, c == null ? 1 : c + 1);
    }

    // ------------------------------------------------------------- okuma

    private static String readAll(InputStream raw) throws IOException {
        java.io.BufferedInputStream in = new java.io.BufferedInputStream(raw);
        in.mark(4);
        int b1 = in.read(), b2 = in.read();
        in.reset();
        byte[] data;
        if (b1 == 'P' && b2 == 'K') {
            ZipInputStream z = new ZipInputStream(in);
            ZipEntry e;
            data = new byte[0];
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) {
                    data = readBytes(z);
                    break;
                }
            }
        } else {
            data = readBytes(in);
        }
        in.close();
        return new String(data, "UTF-8");
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > 60 * 1024 * 1024) break;   // en fazla 60 MB
        }
        return bo.toByteArray();
    }

    /** Dışa aktarılan sohbetten mesaj metinlerini çıkarır; düz yazıysa satırları mesaj sayar. */
    private static List<String> extractMessages(String text) {
        List<String> out = new ArrayList<String>();
        text = text.replace("‎", "").replace("‏", "").replace(" ", " ");
        String[] lines = text.split("\r?\n");
        StringBuilder cur = null;
        int matched = 0;
        for (String line : lines) {
            Matcher m = LINE.matcher(line);
            if (m.matches()) {
                matched++;
                if (cur != null) addMsg(out, cur.toString());
                cur = new StringBuilder(m.group(2));
            } else if (cur != null && !looksLikeStamp(line)) {
                cur.append('\n').append(line);
            } else if (cur != null) {
                addMsg(out, cur.toString());
                cur = null;
            }
        }
        if (cur != null) addMsg(out, cur.toString());
        if (matched == 0) {
            out.clear();
            for (String line : lines) if (line.trim().length() > 0) out.add(line);
        }
        return out;
    }

    private static boolean looksLikeStamp(String line) {
        return line.matches("^\\[?\\d{1,2}[./]\\d{1,2}[./]\\d{2,4},?\\s+\\d{1,2}[:.]\\d{2}.*");
    }

    private static void addMsg(List<String> out, String m) {
        String low = m.toLowerCase(TR);
        if (low.contains("<medya") || low.contains("medya dahil edilmedi") || low.contains("(dosya ekli)")
                || low.contains("bu mesaj silindi") || low.contains("mesaj silindi")
                || low.contains("<media omitted>") || low.contains("görüntü dahil edilmedi")) return;
        out.add(m.replaceAll("https?://\\S+", " "));
    }

    // ------------------------------------------------------------- kelimeleştirme

    private static List<List<String>> sentencesOf(String msg) {
        List<List<String>> res = new ArrayList<List<String>>();
        String low = msg.replace('I', 'ı').replace('İ', 'i').toLowerCase(TR);
        low = low.replace("'", "").replace("’", "").replace("`", "");
        ArrayList<String> cur = new ArrayList<String>();
        StringBuilder w = new StringBuilder();
        for (int i = 0; i <= low.length(); i++) {
            char c = i < low.length() ? low.charAt(i) : ' ';
            if (Character.isLetter(c)) {
                w.append(c);
                continue;
            }
            if (w.length() > 0) {
                String t = w.toString();
                if (t.length() >= 2 && t.length() <= 30) cur.add(t);
                w.setLength(0);
            }
            if (c == '.' || c == '?' || c == '!' || c == '\n' || c == ';' || c == ':' || c == '…') {
                if (!cur.isEmpty()) { res.add(cur); cur = new ArrayList<String>(); }
            }
        }
        if (!cur.isEmpty()) res.add(cur);
        return res;
    }

    private static HashSet<String> loadKnown(Context ctx) {
        HashSet<String> k = new HashSet<String>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open("words_tr.txt"), "UTF-8"), 1 << 16);
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                k.add(t > 0 ? line.substring(0, t) : line.trim());
            }
            r.close();
        } catch (Exception ignored) { }
        return k;
    }

    // ------------------------------------------------------------- hafızaya ekleme

    private static HashMap<String, Integer> parse(String s) {
        HashMap<String, Integer> m = new HashMap<String, Integer>();
        if (s == null) return m;
        for (String e : s.split(",")) {
            int i = e.lastIndexOf(':');
            if (i <= 0) continue;
            try { m.put(e.substring(0, i), Integer.parseInt(e.substring(i + 1))); }
            catch (Exception ignored) { }
        }
        return m;
    }

    private static String dump(HashMap<String, Integer> m, int max, int minCount) {
        List<Map.Entry<String, Integer>> list = new ArrayList<Map.Entry<String, Integer>>();
        for (Map.Entry<String, Integer> e : m.entrySet()) if (e.getValue() >= minCount) list.add(e);
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Integer> e : list) {
            if (n++ >= max) break;
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    private static void add(HashMap<String, Integer> into, HashMap<String, Integer> from) {
        for (Map.Entry<String, Integer> e : from.entrySet()) {
            Integer c = into.get(e.getKey());
            into.put(e.getKey(), (c == null ? 0 : c) + e.getValue());
        }
    }

    private static void merge(Context ctx, HashMap<String, Integer> own, HashMap<String, Integer> pairs,
                              HashMap<String, Integer> triples) {
        SharedPreferences sp = ctx.getSharedPreferences("sesli", Context.MODE_PRIVATE);
        HashMap<String, Integer> o = parse(sp.getString("own", ""));
        HashMap<String, Integer> p = parse(sp.getString("pairs", ""));
        HashMap<String, Integer> t = parse(sp.getString("triples", ""));
        add(o, own);
        add(p, pairs);
        add(t, triples);
        sp.edit()
                .putString("own", dump(o, 9000, 1))
                .putString("pairs", dump(p, 14000, 2))
                .putString("triples", dump(t, 12000, 2))
                .putLong("imported_at", System.currentTimeMillis())
                .apply();
    }
}
