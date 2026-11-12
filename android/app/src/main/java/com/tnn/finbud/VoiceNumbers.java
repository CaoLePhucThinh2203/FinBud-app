package com.tnn.finbud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bản Java của www/voice-parse.js: đọc số tiền nói bằng chữ ("ba mươi lăm nghìn", "một trăm hai mươi nghìn",
 * "hai triệu rưỡi", "bốn lăm ca"...) và chọn phương án nghe tốt nhất. Dùng trong VoiceEngine để mọi nút Nói
 * (widget, pop-up, app) trả về cùng một kết quả đã đổi sang chữ số, ví dụ "ăn bún bò 35.000đ".
 * PHẢI khớp voice-parse.js; kiểm tra bằng tools/widget/check_parser.sh. Không dùng thư viện Android.
 */
public final class VoiceNumbers {

    private VoiceNumbers() {
    }

    public static final class Result {
        public final long a;
        public final String n;
        public final boolean explicit;

        Result(long a, String n, boolean explicit) {
            this.a = a;
            this.n = n;
            this.explicit = explicit;
        }
    }

    private static final Map<String, Integer> D = new HashMap<>();
    private static final Map<String, Double> UNIT = new HashMap<>();
    private static final Set<String> START = new HashSet<>(), CONT = new HashSet<>();
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList("đồng", "đ", "vnd", "vnđ", "đ.", "[unk]"));

    static {
        String[] d = {"một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "bẩy", "tám", "chín"};
        int[] v = {1, 2, 3, 4, 5, 6, 7, 7, 8, 9};
        for (int i = 0; i < d.length; i++) D.put(d[i], v[i]);
        Object[][] u = {{"nghìn", 1e3}, {"ngàn", 1e3}, {"k", 1e3}, {"ca", 1e3}, {"ka", 1e3}, {"triệu", 1e6}, {"tr", 1e6},
            {"củ", 1e6}, {"tỷ", 1e9}, {"xị", 1e5}};
        for (Object[] x : u) UNIT.put((String) x[0], (Double) x[1]);
        START.addAll(D.keySet());
        START.add("mười");
        START.add("nửa");
        CONT.addAll(D.keySet());
        CONT.addAll(Arrays.asList("mười", "nửa", "mươi", "trăm", "chục", "linh", "lẻ", "lăm", "nhăm", "mốt", "tư", "rưỡi", "rưởi"));
        CONT.addAll(UNIT.keySet());
    }

    private static final Pattern NUM1 = Pattern.compile("^\\d+(?:[.,]\\d+)?$"), THOUSANDS = Pattern.compile("^\\d{1,3}(?:[.,]\\d{3})+$");
    private static final String WS = "[\\s\\u00a0]";
    private static final Pattern CURRENCY = Pattern.compile("(\\d{1,3}(?:[.,]\\d{3})+|\\d{4,9})" + WS + "*(?:vnd|vnđ|đ)(?![\\p{L}])");
    private static final Pattern SPLIT_UNIT = Pattern.compile("(\\d)(k|tr)(?![\\p{L}])");

    private static boolean isNum(String t) {
        return NUM1.matcher(t).matches() || THOUSANDS.matcher(t).matches();
    }

    private static double numVal(String t) {
        return THOUSANDS.matcher(t).matches() ? Long.parseLong(t.replaceAll("[.,]", "")) : Double.parseDouble(t.replace(",", "."));
    }

    private static double[] runValue(List<String> tk) {      // trả về {giá trị, explicit ? 1 : 0}
        double total = 0, cur = 0, last = 0, lastUnit = 0, big = 0;
        boolean hasUnit = false;
        for (int i = 0; i < tk.size(); i++) {
            String t = tk.get(i), prev = i > 0 ? tk.get(i - 1) : null;
            if (isNum(t)) {
                double v = numVal(t);
                cur += v;
                last = v == Math.rint(v) && v < 10 ? v : 0;
            } else if (D.containsKey(t)) {
                cur += D.get(t);
                last = D.get(t);
            } else if (t.equals("mười")) {
                cur += 10;
                last = 0;
            } else if (t.equals("nửa")) {
                cur = 0.5;
                last = 0;
            } else if (t.equals("mươi") || t.equals("chục")) {
                cur += last * 9;
                if (last == 0) cur += 10 * (t.equals("chục") ? 1 : 0);
                last = 0;
                lastUnit = t.equals("chục") ? 10 : lastUnit;
            } else if (t.equals("trăm")) {
                cur += last * 99;
                if (last == 0 && cur == 0) cur = 100;
                last = 0;
                lastUnit = 100;
            } else if (t.equals("lăm") || t.equals("nhăm")) {
                if ("mười".equals(prev) || "mươi".equals(prev) || "chục".equals(prev)) cur += 5;
                else if (last != 0) cur += last * 9 + 5;
                else cur += 5;
                last = 0;
            } else if (t.equals("mốt")) {
                if ("mươi".equals(prev) || "chục".equals(prev)) cur += 1;
                else if (last != 0) cur += last * 9 + 1;
                else cur += 1;
                last = 0;
            } else if (t.equals("tư")) {
                cur += 4;
                last = 0;
            } else if (t.equals("linh") || t.equals("lẻ")) {
                last = 0;
            } else if (t.equals("rưỡi") || t.equals("rưởi")) {
                if (lastUnit != 0) {
                    if (lastUnit >= 1000) total += lastUnit / 2;
                    else cur += lastUnit / 2;
                }
            } else if (UNIT.containsKey(t)) {
                double u = UNIT.get(t);
                hasUnit = true;
                total += (cur != 0 ? cur : 1) * u;
                cur = 0;
                lastUnit = u;
                big = u;
                last = 0;
            }
        }
        if (cur > 0) {
            if (big >= 1e3 && total > 0) total += cur < 10 ? cur * big / 10 : cur < 100 ? cur * big / 100 : cur * big / 1000;
            else total += cur;
        }
        boolean explicit = hasUnit || total >= 1000;
        if (!explicit && total > 0) total *= 1000;
        return new double[]{Math.round(total), explicit ? 1 : 0};
    }

    public static Result parseAmt(String text) {
        String raw = text.toLowerCase(Locale.ROOT);
        Matcher m = CURRENCY.matcher(raw);
        if (m.find()) {
            long a = Long.parseLong(m.group(1).replaceAll("[.,]", ""));
            int at = raw.indexOf(m.group());                  // giống String.replace của JS: thay lần xuất hiện đầu tiên
            String n = (raw.substring(0, at) + " " + raw.substring(at + m.group().length())).replaceAll(WS + "+", " ").trim();
            return new Result(a, n, true);
        }
        String[] parts = SPLIT_UNIT.matcher(raw).replaceAll("$1 $2").split(WS + "+");
        List<String> tk = new ArrayList<>();
        for (String p : parts) if (!p.isEmpty()) tk.add(p);
        int bi = -1, bj = -1;
        double bv = 0;
        boolean bex = false;
        for (int i = 0; i < tk.size(); i++) {
            if (!(START.contains(tk.get(i)) || isNum(tk.get(i)))) continue;
            int j = i + 1;
            while (j < tk.size() && (CONT.contains(tk.get(j)) || isNum(tk.get(j)) || SKIP.contains(tk.get(j)))) j++;
            List<String> run = new ArrayList<>();
            for (String t : tk.subList(i, j)) if (!SKIP.contains(t)) run.add(t);
            double[] r = runValue(run);
            if (r[0] >= 1000 && r[0] <= 5e8 && (bi < 0 || r[0] >= bv)) {
                bv = r[0];
                bi = i;
                bj = j;
                bex = r[1] == 1;
            }
            i = j - 1;
        }
        if (bi < 0) return new Result(0, raw.trim(), false);
        StringBuilder note = new StringBuilder();
        for (int k = 0; k < tk.size(); k++) {
            if ((k < bi || k >= bj) && !SKIP.contains(tk.get(k))) {
                if (note.length() > 0) note.append(' ');
                note.append(tk.get(k));
            }
        }
        return new Result((long) bv, note.length() > 0 ? note.toString() : "chi tiêu", bex);
    }

    /** Chọn phương án nghe đầu tiên có số tiền rõ ràng, rồi tới phương án có số tiền, cuối cùng là phương án đầu. */
    public static String pickVoiceText(String text, List<String> alternatives) {
        List<String> list = new ArrayList<>();
        if (text != null && !text.isEmpty()) list.add(text);
        if (alternatives != null) for (String s : alternatives) if (s != null && !s.isEmpty()) list.add(s);
        for (String t : list) {
            Result r = parseAmt(t);
            if (r.a > 0 && r.explicit) return t;
        }
        for (String t : list) if (parseAmt(t).a > 0) return t;
        return list.isEmpty() ? "" : list.get(0);
    }

    /** Kết quả cuối cho ô nhập: "ăn bún bò ba mươi lăm nghìn" -> "ăn bún bò 35.000đ" (giống normalizeVoice trong app). */
    public static String normalize(String text, List<String> alternatives) {
        String t = pickVoiceText(text, alternatives);
        Result r = parseAmt(t);
        if (r.a <= 0) return t;
        String n = r.n.equals("chi tiêu") ? "" : r.n;
        return (n + " " + money(r.a)).trim();
    }

    /** 35000 -> "35.000đ" (giống fmt() trong app). */
    public static String money(long a) {
        String s = Long.toString(Math.abs(a));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append('.');
            b.append(s.charAt(i));
        }
        return (a < 0 ? "-" : "") + b + "đ";
    }
}
