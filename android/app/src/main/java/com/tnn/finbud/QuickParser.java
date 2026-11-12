package com.tnn.finbud;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bản Java của phần "tách số tiền + phân loại danh mục" trong app, dùng cho bảng nhập nhanh từ widget
 * (QuickAddActivity) để không phải mở WebView.
 *
 * PHẢI cho kết quả giống hệt:
 *   - parseAmt(), classifyRx()            trong www/index.html
 *   - fold(), normTok(), categorize()     trong www/receipt-engine.js
 * Kiểm tra bằng: tools/widget/check_parser.sh (so Java với JS trên bộ câu test).
 * File này cố ý không dùng thư viện Android để chạy thử được bằng javac trên máy tính.
 */
public final class QuickParser {

    private QuickParser() {
    }

    // ---------------- Tách số tiền (parseAmt) ----------------

    public static final class Amount {
        public final long a;      // số tiền, 0 nếu không thấy
        public final String n;    // phần chữ còn lại (ghi chú)

        Amount(long a, String n) {
            this.a = a;
            this.n = n;
        }
    }

    private static final String WS = "[\\s\\u00a0]";
    private static final Map<String, Integer> W = new HashMap<>();
    private static final Map<String, Long> U = new HashMap<>();

    static {
        String[] w = {"một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín", "mười"};
        for (int i = 0; i < w.length; i++) W.put(w[i], i + 1);
        U.put("k", 1000L);
        U.put("nghìn", 1000L);
        U.put("ngàn", 1000L);
        U.put("xị", 100_000L);
        U.put("chục", 10_000L);
        U.put("triệu", 1_000_000L);
        U.put("tr", 1_000_000L);
    }

    private static final Pattern HALF_MILLION = Pattern.compile("nửa triệu");
    private static final Pattern WITH_UNIT = Pattern.compile(
        "(?<![\\p{L}\\d])(\\d+(?:[.,]\\d+)?|một|hai|ba|bốn|năm|sáu|bảy|tám|chín|mười)(?:" + WS + "*(lăm|mươi))?"
            + WS + "*(k|nghìn|ngàn|xị|chục|triệu|tr)(" + WS + "*rưỡi)?(?![\\p{L}])");
    private static final Pattern WITH_CURRENCY = Pattern.compile("(\\d{1,3}(?:[.,]\\d{3})+|\\d+)" + WS + "*(?:vnd|vnđ|đ)(?![\\p{L}])");
    private static final Pattern ANY_NUMBER = Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+|\\d+");

    public static Amount parseAmt(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        Matcher m = HALF_MILLION.matcher(s);
        if (m.find()) return new Amount(500_000, cut(s, m));

        m = WITH_UNIT.matcher(s);
        if (m.find()) {
            String g1 = m.group(1);
            double n = W.containsKey(g1) ? W.get(g1) : Double.parseDouble(g1.replace(",", "."));
            if ("lăm".equals(m.group(2))) n = n * 10 + 5;
            else if ("mươi".equals(m.group(2))) n = n * 10;
            long u = U.get(m.group(3));
            return new Amount(Math.round(n * u + (m.group(4) != null ? u / 2.0 : 0)), cut(s, m));
        }

        m = WITH_CURRENCY.matcher(s);
        String digits = null;
        if (m.find()) digits = m.group(1);
        else {
            m = ANY_NUMBER.matcher(s);
            if (m.find()) digits = m.group();
        }
        if (digits != null) {
            long a = Long.parseLong(digits.replaceAll("[.,]", ""));
            if (a < 1000) a *= 1000;
            return new Amount(a, cut(s, m));
        }
        return new Amount(0, s);
    }

    /** Bỏ đoạn số tiền ra khỏi câu, giống cut() trong JS. */
    private static String cut(String s, Matcher m) {
        return (s.substring(0, m.start()) + " " + s.substring(m.end())).replaceAll(WS + "+", " ").trim();
    }

    /** Viết hoa chữ cái đầu, rỗng thì "Chi tiêu" (giống analyze() trong app). */
    public static String noteOf(Amount p) {
        String n = p.n == null || p.n.isEmpty() ? "Chi tiêu" : p.n;
        int c = n.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(c))) + n.substring(Character.charCount(c));
    }

    // ---------------- Chuẩn hoá chữ ----------------

    private static final Pattern MARKS = Pattern.compile("[\\u0300-\\u036f]");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    static String fold(String s) {
        String x = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        x = MARKS.matcher(x).replaceAll("").replace("đ", "d");
        return x.replaceAll(WS + "+", " ").trim();
    }

    static String normTok(String s) {
        return NON_ALNUM.matcher(fold(s)).replaceAll(" ").trim();
    }

    // ---------------- Phân loại danh mục ----------------

    /** Mô hình + từ điển, nạp từ receipt_model.json (mục "cat") và receipt_lexicon.json. */
    public static final class Classifier {
        final String[] classes;
        final Map<String, Integer> vocab;
        final double[] idf;
        final double[][] coef;
        final double[] intercept;
        final List<String> categories;                       // thứ tự danh mục trong từ điển
        final LinkedHashMap<String, List<Object[]>> catKw;   // danh mục -> [[từ khoá, trọng số], ...]

        public Classifier(String[] classes, Map<String, Integer> vocab, double[] idf, double[][] coef, double[] intercept,
                          List<String> categories, LinkedHashMap<String, List<Object[]>> catKw) {
            this.classes = classes;
            this.vocab = vocab;
            this.idf = idf;
            this.coef = coef;
            this.intercept = intercept;
            this.categories = categories;
            this.catKw = catKw;
        }

        public String categorize(String text) {
            String t = normTok(text);
            if (classes != null && classes.length > 0) {
                LinkedHashMap<String, Integer> p = new LinkedHashMap<>();
                for (String w0 : t.split(" ")) {
                    if (w0.isEmpty()) continue;
                    String w = " " + w0 + " ";
                    for (int n = 2; n <= 4; n++) {
                        int off = 0;
                        add(p, w.substring(0, Math.min(n, w.length())));
                        while (off + n < w.length()) {
                            off++;
                            add(p, w.substring(off, off + n));
                        }
                        if (off == 0) break;
                    }
                }
                LinkedHashMap<Integer, Double> x = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> e : p.entrySet()) {
                    Integer j = vocab.get(e.getKey());
                    if (j != null) x.put(j, (1 + Math.log(e.getValue())) * idf[j]);
                }
                double ss = 0;
                for (double v : x.values()) ss += v * v;
                double nrm = Math.sqrt(ss);
                if (nrm == 0) nrm = 1;
                double[] z = new double[classes.length];
                for (int k = 0; k < z.length; k++) {
                    double s = intercept[k];
                    for (Map.Entry<Integer, Double> e : x.entrySet()) s += coef[k][e.getKey()] * e.getValue() / nrm;
                    z[k] = s;
                }
                double mz = Double.NEGATIVE_INFINITY;
                for (double v : z) mz = Math.max(mz, v);
                double sm = 0;
                double[] e = new double[z.length];
                for (int k = 0; k < z.length; k++) sm += (e[k] = Math.exp(z[k] - mz));
                int best = 0;
                for (int k = 1; k < z.length; k++) if (e[k] > e[best]) best = k;
                if (e[best] / sm >= 0.45) return classes[best];
            }
            String pad = " " + t + " ";
            LinkedHashMap<String, Double> sc = new LinkedHashMap<>();
            for (String c : categories) sc.put(c, 0.0);
            for (Map.Entry<String, List<Object[]>> e : catKw.entrySet()) {
                for (Object[] kw : e.getValue()) {
                    if (pad.contains(" " + kw[0] + " ")) sc.put(e.getKey(), sc.getOrDefault(e.getKey(), 0.0) + ((Number) kw[1]).doubleValue());
                }
            }
            String best = null;
            for (String c : sc.keySet()) if (best == null || sc.get(c) > sc.get(best)) best = c;
            return best != null && sc.get(best) > 0 ? best : "other";
        }

        private static void add(Map<String, Integer> p, String g) {
            p.put(g, p.getOrDefault(g, 0) + 1);
        }
    }

    /** Luật đơn giản dự phòng khi chưa nạp được mô hình (giống classifyRx() trong app). */
    public static String classifyRx(String s) {
        s = s.toLowerCase(Locale.ROOT);
        if (Pattern.compile("ăn|bún|cơm|phở|trà sữa|cà phê|cafe|bánh|nước|xôi|uống|quán").matcher(s).find()) return "food";
        if (Pattern.compile("xe|xăng|grab|bus|buýt|taxi|be ").matcher(s).find()) return "move";
        if (Pattern.compile("sách|photo|học|in ấn|văn phòng").matcher(s).find()) return "study";
        if (Pattern.compile("mua|áo|quần|siêu thị|shop|giày").matcher(s).find()) return "shop";
        return "other";
    }

    /** Danh sách danh mục hiển thị, cùng thứ tự và màu với CATS trong app. */
    public static final String[][] CATS = {
        {"food", "Ăn uống", "#D85A30"}, {"move", "Di chuyển", "#378ADD"}, {"study", "Học tập", "#7F77DD"},
        {"shop", "Mua sắm", "#D4537E"}, {"health", "Sức khỏe", "#E24B4A"}, {"fun", "Giải trí", "#639922"},
        {"other", "Khác", "#888780"},
    };

    static List<String> catKeys() {
        List<String> k = new ArrayList<>();
        for (String[] c : CATS) k.add(c[0]);
        return k;
    }
}
