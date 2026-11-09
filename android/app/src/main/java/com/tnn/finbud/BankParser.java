package com.tnn.finbud;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phân tích nội dung thông báo biến động số dư. Không phụ thuộc Android để kiểm thử được trên máy tính.
 * Chỉ trả về: chiều giao dịch, số tiền, nội dung ngắn. KHÔNG giữ số tài khoản, số dư hay mã giao dịch.
 */
public final class BankParser {
    private BankParser() {}

    public static final class Result {
        public final boolean outgoing;
        public final long amount;
        public final String note;

        Result(boolean outgoing, long amount, String note) {
            this.outgoing = outgoing;
            this.amount = amount;
            this.note = note;
        }
    }

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    // Chạy trên văn bản đã bỏ dấu (đ -> d), nên đơn vị tiền là "vnd" hoặc "d".
    private static final Pattern SIGNED = Pattern.compile(
        "(?<![a-z0-9])([+\\-\\u2212\\u2013])\\s?(\\d{1,3}(?:[.,]\\d{3})+|\\d{4,})\\s?(?:vnd|d)?(?![a-z0-9])", F);
    private static final Pattern UNIT_AMT = Pattern.compile(
        "(?<![a-z0-9.,])(\\d{1,3}(?:[.,]\\d{3})+|\\d{4,})\\s?(?:vnd|d)(?![a-z0-9])", F);
    private static final Pattern BALANCE = Pattern.compile(
        "(?<![a-z0-9])(so du|sd)( cuoi| kha dung)?\\s*:?\\s*[+\\-]?\\s*\\d[\\d.,]*\\s?(?:vnd|d)?(?![a-z0-9])", F);
    private static final Pattern NOTE_ND = Pattern.compile(
        "(?<![a-z0-9])(noi dung|nd|mo ta|loi nhan|content)\\s*:\\s*", F);
    private static final Pattern NOTE_TO = Pattern.compile("(?<![a-z0-9])(cho|toi|tai|den)\\s+", F);
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{6,}");

    private static final String[] SENSITIVE = {"otp", "ma xac thuc", "ma xac nhan", "mat khau", "ma kich hoat", "ma bao mat", "password",
        "verification code", "one time password", "one-time password", "security code", "ma dang nhap"};
    private static final String[] OUT_KW = {"thanh toan", "chuyen tien", "chuyen khoan", "rut tien", "tru tien", "ghi no", "gd no", "debit", "mua"};
    private static final String[] IN_KW = {"nhan tien", "da nhan", "nhan duoc", "chuyen den", "cong tien", "ghi co", "credit", "hoan tien"};

    /** Bỏ dấu tiếng Việt, hạ chữ thường; giữ nguyên độ dài với văn bản NFC. */
    static String fold(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= '\u0300' && c <= '\u036f') continue;
            if (c == '\u0111' || c == '\u0110') c = 'd';
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** Thông báo chứa OTP / mật khẩu: phải bỏ ngay, không xử lý, không lưu. */
    public static boolean isSensitive(String text) {
        String f = fold(text);
        for (String k : SENSITIVE) {
            if (k.length() <= 3 ? Pattern.compile("(?<![a-z0-9])" + k + "(?![a-z0-9])").matcher(f).find() : f.contains(k)) return true;
        }
        return false;
    }

    /** Thay mọi chữ số bằng '#': giữ lại khuôn mẫu để gỡ lỗi mà không lộ số tiền, số tài khoản. */
    public static String mask(String text) {
        String t = text.replaceAll("\\d", "#").replaceAll("\\s+", " ").trim();
        return t.length() > 220 ? t.substring(0, 220) : t;
    }

    /** Chữ ký (SHA-256, 12 ký tự đầu) của nội dung thông báo. Chỉ lưu chữ ký để chống trùng, không lưu văn bản gốc. */
    public static String signature(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.trim().replaceAll("\\s+", " ").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * entry có dạng "gói|số tiền|chữ ký|thời gian ms".
     * Trùng nếu: (a) cùng gói + số tiền + chữ ký (đúng thông báo cũ bị đẩy lại), hoặc
     *            (b) cùng gói + số tiền và cách nhau chưa quá windowMs (cùng giao dịch nhưng câu chữ khác).
     */
    public static boolean isDuplicate(String entry, String pkg, long amount, String sig, long ts, long windowMs) {
        String[] p = entry.split("\\|");
        if (p.length < 4 || !p[0].equals(pkg) || !p[1].equals(String.valueOf(amount))) return false;
        if (p[2].equals(sig)) return true;
        try {
            return Math.abs(ts - Long.parseLong(p[3])) <= windowMs;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean hasAny(String f, String[] kws) {
        for (String k : kws) if (f.contains(k)) return true;
        return false;
    }

    private static long toAmount(String digits) {
        try {
            return Long.parseLong(digits.replaceAll("[.,]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** @return null nếu không nhận ra giao dịch (số tiền hoặc chiều chưa rõ). */
    public static Result parse(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String orig = Normalizer.normalize(text, Normalizer.Form.NFC);
        String f = fold(orig);
        boolean aligned = f.length() == orig.length();
        String src = aligned ? orig : f;

        // Che phần "Số dư ..." để không nhầm với số tiền giao dịch (giữ nguyên độ dài để khớp vị trí).
        char[] cs = f.toCharArray();
        Matcher bm = BALANCE.matcher(f);
        while (bm.find()) for (int i = bm.start(); i < bm.end(); i++) cs[i] = ' ';
        String g = new String(cs);

        boolean outgoing;
        long amount;
        int amtEnd;
        Matcher sm = SIGNED.matcher(g);
        if (sm.find()) {
            outgoing = !sm.group(1).equals("+");
            amount = toAmount(sm.group(2));
            amtEnd = sm.end();
        } else {
            Matcher um = UNIT_AMT.matcher(g);
            if (!um.find()) return null;
            amount = toAmount(um.group(1));
            amtEnd = um.end();
            boolean out = hasAny(f, OUT_KW), in = hasAny(f, IN_KW);
            if (out == in) return null;      // không rõ chiều -> không đoán
            outgoing = out;
        }
        if (amount < 1000 || amount > 5_000_000_000L) return null;
        return new Result(outgoing, amount, extractNote(src, f, amtEnd));
    }

    private static String extractNote(String src, String f, int amtEnd) {
        String note = "";
        Matcher nd = NOTE_ND.matcher(f);
        if (nd.find()) {
            note = src.substring(nd.end());
            int cut = indexOfBalance(f, nd.end());
            if (cut >= 0) note = src.substring(nd.end(), cut);
        } else {
            Matcher to = NOTE_TO.matcher(f);
            if (to.find(amtEnd)) note = src.substring(to.end());
        }
        int dot = note.indexOf(". ");
        if (dot > 0) note = note.substring(0, dot);
        note = LONG_DIGITS.matcher(note).replaceAll("");
        note = note.replaceAll("[\\s.:;,\\-]+$", "").replaceAll("\\s+", " ").trim();
        return note.length() > 60 ? note.substring(0, 60).trim() : note;
    }

    private static int indexOfBalance(String f, int from) {
        Matcher m = Pattern.compile("(?<![a-z0-9])(so du|sd)(?![a-z0-9])").matcher(f);
        return m.find(from) ? m.start() : -1;
    }
}
