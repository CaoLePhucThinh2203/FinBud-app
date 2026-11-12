package com.tnn.finbud;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.provider.Settings;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Tiện ích cho VoiceEngine (hệ thống nhận giọng dùng chung của app, pop-up và widget):
 *  - chọn dịch vụ nhận giọng: ưu tiên Google vì dịch vụ mặc định của một số hãng (Xiaomi, Samsung, Oppo...)
 *    không hỗ trợ tiếng Việt và báo lỗi giống như mất mạng;
 *  - đổi mã lỗi thành câu dễ hiểu (không gộp hết thành "Không có mạng");
 *  - ghi lại lỗi gần nhất (kể cả lỗi văng app) để xem trong Cài đặt → Chẩn đoán.
 */
final class SpeechHelper {

    private SpeechHelper() {
    }

    private static final String[] PREFERRED = {
        "com.google.android.googlequicksearchbox",   // ứng dụng Google
        "com.google.android.tts",                     // Dịch vụ lời nói của Google
    };
    static final String KEY_LAST_ERROR = "last_error";

    /** Dịch vụ nhận giọng sẽ dùng; null = để Android tự chọn dịch vụ mặc định. */
    static ComponentName pickService(Context ctx) {
        List<ResolveInfo> list = ctx.getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        for (String pkg : PREFERRED) {
            for (ResolveInfo r : list) {
                ServiceInfo s = r.serviceInfo;
                if (s != null && pkg.equals(s.packageName)) return new ComponentName(s.packageName, s.name);
            }
        }
        return null;
    }

    static boolean available(Context ctx) {
        return SpeechRecognizer.isRecognitionAvailable(ctx) || pickService(ctx) != null;
    }

    static boolean onDeviceAvailable(Context ctx) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx);
    }

    /** Tạo bộ nhận giọng trực tuyến (ưu tiên Google). Gọi trên luồng giao diện. */
    static SpeechRecognizer createOnline(Context ctx) {
        ComponentName c = pickService(ctx);
        return c != null ? SpeechRecognizer.createSpeechRecognizer(ctx, c) : SpeechRecognizer.createSpeechRecognizer(ctx);
    }

    /** offline = true chỉ dùng cho bộ nhận on-device; dịch vụ trực tuyến không gửi cờ này (tránh lỗi "mất mạng" giả). */
    static Intent intent(boolean offline) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        if (offline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        return i;
    }

    static boolean isNetwork(int code) {
        return code == SpeechRecognizer.ERROR_NETWORK || code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT;
    }

    /** Lỗi không phải do người nói (mạng, ngôn ngữ, dịch vụ): nên chuyển sang Vosk. */
    static boolean shouldFallback(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
            case SpeechRecognizer.ERROR_CLIENT:
            case 10:  // ERROR_TOO_MANY_REQUESTS
            case 11:  // ERROR_SERVER_DISCONNECTED
            case 12:  // ERROR_LANGUAGE_NOT_SUPPORTED
            case 13:  // ERROR_LANGUAGE_UNAVAILABLE
                return true;
            default:
                return false;
        }
    }

    static String message(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Không nghe rõ, bạn thử lại nhé";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Cần cấp quyền micro để nói";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Micro đang bận, thử lại sau giây lát";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Dịch vụ nhận giọng không kết nối được mạng";
            case SpeechRecognizer.ERROR_SERVER:
            case 11:
                return "Máy chủ nhận giọng đang lỗi";
            case 12:
            case 13:
                return "Dịch vụ nhận giọng trên máy chưa hỗ trợ tiếng Việt";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Dịch vụ nhận giọng không phản hồi";
            default:
                return "Nhận giọng nói gặp lỗi";
        }
    }

    /** Ghi lỗi gần nhất (nơi xảy ra + mã + mô tả) để xem trong Cài đặt → Chẩn đoán. */
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

    static void recordError(Context ctx, String where, int code) {
        recordError(ctx, where, "mã " + code + ": " + message(code) + " · dịch vụ: " + serviceName(ctx));
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

    static String serviceName(Context ctx) {
        ComponentName c = pickService(ctx);
        if (c != null) return c.getPackageName();
        String def = Settings.Secure.getString(ctx.getContentResolver(), "voice_recognition_service");
        return def != null && !def.isEmpty() ? def + " (mặc định)" : "mặc định của máy";
    }

    /** Thông tin để hiện trong Cài đặt → Chẩn đoán. */
    static JSONObject diagnostics(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            JSONArray all = new JSONArray();
            for (ResolveInfo r : ctx.getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0)) {
                if (r.serviceInfo != null) all.put(r.serviceInfo.packageName);
            }
            o.put("service", serviceName(ctx));
            o.put("services", all);
            o.put("onDevice", onDeviceAvailable(ctx) ? VoiceEngine.onDeviceStatus() : "máy không hỗ trợ");
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
