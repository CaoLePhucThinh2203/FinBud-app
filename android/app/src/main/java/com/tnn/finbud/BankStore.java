package com.tnn.finbud;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Kho lưu cục bộ cho tính năng đọc thông báo ngân hàng (SharedPreferences riêng của app).
 * Chỉ lưu giao dịch đã phân tích (số tiền, nội dung ngắn, thời gian). Không lưu nội dung thông báo gốc.
 */
final class BankStore {
    private BankStore() {}

    /** {tên gói Android, tên hiển thị}. Muốn thêm ngân hàng: thêm một dòng ở đây. */
    static final String[][] BANKS = {
        {"com.mbmobile", "MB Bank"},
        {"com.VCB", "Vietcombank"},
        {"vn.com.techcombank.bb.app", "Techcombank"},
        {"com.mservice.momotransfer", "MoMo"},
        {"vn.com.vng.zalopay", "ZaloPay"},
        {"com.vnpay.bidv", "BIDV"},
        {"com.vietinbank.ipay", "VietinBank"},
        {"mobile.acb.com.vn", "ACB"},
        {"com.tpb.mb.gprsandroid", "TPBank"},
        {"com.vnpay.vpbankonline", "VPBank"},
        {"src.com.sacombank", "Sacombank"},
        {"com.vnpay.Agribank3g", "Agribank"},
        {"com.vnpay.hdbank", "HDBank"},
        {"vn.com.msb.smartBanking", "MSB"},
        {"com.ocb.omniextra", "OCB"},
        {"vn.shb.mbanking", "SHB"},
        {"com.bplus.vtpay", "Viettel Money"}
    };

    private static final String PREFS = "finbud_bank";
    private static final int MAX_PENDING = 200;
    private static final int MAX_SEEN = 300;
    private static final int MAX_SAMPLES = 10;
    /** Cùng ngân hàng + cùng số tiền trong khoảng này được coi là một giao dịch (đổi số nếu muốn chặt/lỏng hơn). */
    private static final long DUP_WINDOW_MS = 2 * 60 * 1000L;
    private static final Object LOCK = new Object();

    static String labelOf(String pkg) {
        if (pkg == null) return null;
        for (String[] b : BANKS) {
            if (b[0].equals(pkg)) return b[1];
        }
        return null;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray readArray(SharedPreferences p, String key) {
        try {
            return new JSONArray(p.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /** Mặc định TẮT cho mọi ngân hàng: người dùng phải tự bật từng cái. */
    static boolean isEnabled(Context c, String pkg) {
        synchronized (LOCK) {
            JSONArray a = readArray(prefs(c), "enabled");
            for (int i = 0; i < a.length(); i++) {
                if (pkg.equals(a.optString(i))) return true;
            }
            return false;
        }
    }

    static void setEnabled(Context c, String pkg, boolean enabled) {
        if (labelOf(pkg) == null) return;
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            JSONArray old = readArray(p, "enabled");
            JSONArray next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                String s = old.optString(i);
                if (!s.equals(pkg)) next.put(s);
            }
            if (enabled) next.put(pkg);
            p.edit().putString("enabled", next.toString()).apply();
        }
    }

    /** @return true nếu là giao dịch mới. sig = BankParser.signature(nội dung gốc). */
    static boolean addTx(Context c, String pkg, long amount, String note, long ts, String sig) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            String id = pkg + "|" + amount + "|" + sig;
            JSONArray seen = readArray(p, "seen");
            for (int i = 0; i < seen.length(); i++) {
                if (BankParser.isDuplicate(seen.optString(i), pkg, amount, sig, ts, DUP_WINDOW_MS)) return false;
            }
            seen.put(id + "|" + ts);
            JSONArray pending = readArray(p, "pending");
            try {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("amount", amount);
                o.put("note", note);
                o.put("ts", ts);
                o.put("bank", labelOf(pkg));
                pending.put(o);
            } catch (JSONException e) {
                return false;
            }
            p.edit()
                .putString("seen", trim(seen, MAX_SEEN).toString())
                .putString("pending", trim(pending, MAX_PENDING).toString())
                .apply();
            return true;
        }
    }

    static JSONArray getPending(Context c) {
        synchronized (LOCK) {
            return readArray(prefs(c), "pending");
        }
    }

    /** JS đã lưu xong các giao dịch này -> xóa khỏi hàng đợi. */
    static void ack(Context c, JSONArray ids) {
        if (ids == null) return;
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            JSONArray old = readArray(p, "pending");
            JSONArray next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                boolean done = false;
                for (int k = 0; k < ids.length(); k++) {
                    if (o.optString("id").equals(ids.optString(k))) {
                        done = true;
                        break;
                    }
                }
                if (!done) next.put(o);
            }
            p.edit().putString("pending", next.toString()).apply();
        }
    }

    /** Mẫu thông báo đọc không ra: đã thay mọi chữ số bằng '#'. */
    static void addSample(Context c, String bank, String masked) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            JSONArray a = readArray(p, "samples");
            a.put(bank + ": " + masked);
            p.edit().putString("samples", trim(a, MAX_SAMPLES).toString()).apply();
        }
    }

    static JSONArray getSamples(Context c) {
        synchronized (LOCK) {
            return readArray(prefs(c), "samples");
        }
    }

    static void clearSamples(Context c) {
        synchronized (LOCK) {
            prefs(c).edit().putString("samples", "[]").apply();
        }
    }

    private static JSONArray trim(JSONArray a, int max) {
        if (a.length() <= max) return a;
        JSONArray r = new JSONArray();
        for (int i = a.length() - max; i < a.length(); i++) {
            try {
                r.put(a.get(i));
            } catch (JSONException ignored) {
            }
        }
        return r;
    }
}
