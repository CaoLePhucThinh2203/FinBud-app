package com.tnn.finbud;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tiện ích cho VoiceEngine (hệ thống nhận giọng dùng chung của app, pop-up và widget):
 *  - ghi lại lỗi gần nhất (kể cả lỗi văng app) để xem trong Cài đặt → Chẩn đoán;
 *  - tổng hợp thông tin chẩn đoán.
 */
final class SpeechHelper {

    private SpeechHelper() {
    }

    static final String KEY_LAST_ERROR = "last_error";

    /** Ghi lỗi gần nhất (nơi xảy ra + mô tả) để xem trong Cài đặt → Chẩn đoán. */
    static void recordError(Context ctx, String where, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("time", new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date()));
            o.put("where", where);
            o.put("detail", detail.length() > 1500 ? detail.substring(0, 1500) : detail);
            ctx.getSharedPreferences(FinBudWidget.PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_ERROR, o.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    static String stack(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString();
    }

    /** Ghi lại lỗi văng app (rồi để Android xử lý như bình thường). */
    static void installCrashLog(Context ctx, String where) {
        Context app = ctx.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof CrashLogger) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashLogger(app, where, prev));
    }

    private static final class CrashLogger implements Thread.UncaughtExceptionHandler {
        private final Context ctx;
        private final String where;
        private final Thread.UncaughtExceptionHandler prev;

        CrashLogger(Context ctx, String where, Thread.UncaughtExceptionHandler prev) {
            this.ctx = ctx;
            this.where = where;
            this.prev = prev;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            recordError(ctx, where + " (văng app)", stack(e));
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    /** Thông tin để hiện trong Cài đặt → Chẩn đoán. */
    static JSONObject diagnostics(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("zip", VoiceEngine.zipStatus());
            o.put("vosk", VoiceEngine.voskStatus());
            o.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            String last = ctx.getSharedPreferences(FinBudWidget.PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_ERROR, null);
            if (last != null) o.put("lastError", new JSONObject(last));
        } catch (Exception ignored) {
        }
        return o;
    }
}
