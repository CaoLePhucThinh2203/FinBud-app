package com.tnn.finbud;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.SpeechRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * MỘT hệ thống nhận giọng dùng chung cho cả 3 nút Nói: nút Nói trên widget, nút Nói trong pop-up nhập nhanh
 * (QuickAddActivity) và nút Nói trong app (qua VoicePlugin).
 *
 *   Chế độ "Chính xác nhất": nhận giọng của Android (ưu tiên Google) -> lỗi mạng / dịch vụ / ngôn ngữ -> Vosk.
 *   Chế độ "Chỉ trên máy":   bộ nhận giọng on-device của Android nếu máy có gói tiếng Việt -> không có -> Vosk.
 *                            Không bao giờ gửi âm thanh lên mạng.
 *
 * Kết quả luôn đã chọn phương án nghe tốt nhất và đổi số đọc bằng chữ thành chữ số (VoiceNumbers),
 * ví dụ "ăn bún bò ba mươi lăm nghìn" -> "ăn bún bò 35.000đ".
 * Model Vosk nạp một lần, dùng chung cho cả app và pop-up. Mọi hàm gọi trên luồng giao diện.
 * Người gọi phải xin quyền RECORD_AUDIO trước khi start().
 */
final class VoiceEngine {

    interface Callback {
        void onState(String state);                  // "loading" (đang nạp Vosk) | "listening" | "idle"
        void onPartial(String text);                 // chữ tạm khi đang nói
        void onResult(String text, String engine);   // text đã đổi số; engine = "android" | "ondevice" | "vosk"
        void onInfo(String message);                 // thông báo phụ, ví dụ lý do chuyển sang Vosk
        void onError(String message, int code);      // kết thúc mà không có kết quả
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---- Vosk: model dùng chung cho cả process ----
    private static Model voskModel;
    private static boolean voskLoading;
    private static String voskLoadError;
    private static final List<Runnable> voskWaiters = new ArrayList<>();

    // ---- Gói tiếng Việt trên máy: null = chưa biết, kiểm tra một lần (Android 13+) ----
    private static Boolean viOnDevice;

    private final Context ctx;
    private final Callback cb;
    private SpeechRecognizer recognizer;
    private SpeechService vosk;
    private boolean active;        // đang trong một lượt nghe (chưa trả kết quả / lỗi)
    private boolean stopping;      // người dùng bấm Dừng
    private String engine = "";

    VoiceEngine(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    boolean isActive() {
        return active;
    }

    // ================= Bắt đầu / dừng =================

    void start(boolean deviceOnly) {
        release();
        active = true;
        stopping = false;
        if (!deviceOnly) {
            if (SpeechHelper.available(ctx)) startAndroid(false);
            else startVosk(null);                             // máy không có dịch vụ nhận giọng nào
        } else if (canUseOnDevice()) {
            startAndroid(true);
        } else {
            startVosk(null);
        }
    }

    /** Dừng nghe; phần đã nói vẫn được nhận dạng và trả về qua onResult. */
    void stop() {
        if (!active) return;
        stopping = true;
        if (recognizer != null) recognizer.stopListening();
        else if (vosk != null) vosk.stop();                  // Vosk gửi onFinalResult với phần còn lại
        else finish(null, null, null, -1);                   // đang nạp model: dừng luôn, không báo lỗi
    }

    /** Huỷ hẳn, không trả kết quả (đóng pop-up, rời màn hình). */
    void release() {
        active = false;
        destroyRecognizer();
        if (vosk != null) {
            try {
                vosk.stop();
                vosk.shutdown();
            } catch (Exception ignored) {
            }
            vosk = null;
        }
    }

    /** Nạp trước những gì chế độ này sẽ cần, để lần bấm Nói đầu tiên không phải chờ. */
    static void prepare(Context ctx, boolean deviceOnly) {
        Context app = ctx.getApplicationContext();
        checkOnDeviceSupport(app);
        if (deviceOnly || !SpeechHelper.available(app)) loadVosk(app, null);
    }

    private void finish(String text, String eng, String err, int code) {
        if (!active) return;
        active = false;
        destroyRecognizer();
        if (vosk != null) {
            try {
                vosk.shutdown();
            } catch (Exception ignored) {
            }
            vosk = null;
        }
        cb.onState("idle");
        if (text != null && !text.trim().isEmpty()) cb.onResult(text.trim(), eng);
        else if (err != null) cb.onError(err, code);
    }

    // ================= Nhận giọng của Android =================

    private boolean canUseOnDevice() {
        return SpeechHelper.onDeviceAvailable(ctx) && !Boolean.FALSE.equals(viOnDevice);
    }

    private void startAndroid(boolean onDevice) {
        engine = onDevice ? "ondevice" : "android";
        try {
            recognizer = onDevice ? SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) : SpeechHelper.createOnline(ctx);
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) {}
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float v) {}
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() {}
                public void onEvent(int t, Bundle b) {}

                public void onPartialResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (active && r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) cb.onPartial(r.get(0));
                }

                public void onResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r == null || r.isEmpty()) finish(null, null, SpeechHelper.message(SpeechRecognizer.ERROR_NO_MATCH), SpeechRecognizer.ERROR_NO_MATCH);
                    else finish(VoiceNumbers.normalize(r.get(0), r.subList(1, r.size())), engine, null, 0);
                }

                public void onError(int code) {
                    onAndroidError(code, onDevice);
                }
            });
            recognizer.startListening(SpeechHelper.intent(onDevice));
            cb.onState("listening");
        } catch (Exception e) {
            SpeechHelper.recordError(ctx, "nhận giọng Android: bật", SpeechHelper.stack(e));
            destroyRecognizer();                              // phải nhả micro trước khi Vosk mở micro
            startVosk("Không bật được nhận giọng của Android");
        }
    }

    private void onAndroidError(int code, boolean onDevice) {
        destroyRecognizer();                                  // nhả micro ngay (trước đây quên bước này nên Vosk không mở được micro)
        if (!active) return;
        if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            || code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || stopping) {
            finish(null, null, SpeechHelper.message(code), code);
            return;
        }
        SpeechHelper.recordError(ctx, onDevice ? "nhận giọng trên máy" : "nhận giọng Android", code);
        if (onDevice && code != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) viOnDevice = false;   // lần sau đi thẳng Vosk
        if (onDevice || SpeechHelper.shouldFallback(code)) {
            startVosk(onDevice ? "Máy chưa có gói tiếng Việt ngoại tuyến" : SpeechHelper.message(code) + " (mã " + code + ")");
        } else {
            finish(null, null, SpeechHelper.message(code) + " (mã " + code + ")", code);
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }

    /** Android 13+: hỏi trước xem bộ nhận on-device đã có tiếng Việt chưa; có thể tải thì nhờ máy tải về. */
    private static void checkOnDeviceSupport(Context ctx) {
        if (viOnDevice != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !SpeechHelper.onDeviceAvailable(ctx)) return;
        MAIN.post(() -> {
            try {
                SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx);
                Intent i = SpeechHelper.intent(true);
                r.checkRecognitionSupport(i, Executors.newSingleThreadExecutor(), new RecognitionSupportCallback() {
                    @Override
                    public void onSupportResult(RecognitionSupport s) {
                        boolean installed = hasVi(s.getInstalledOnDeviceLanguages());
                        viOnDevice = installed;
                        if (!installed && (hasVi(s.getSupportedOnDeviceLanguages()) || hasVi(s.getPendingOnDeviceLanguages()))) {
                            MAIN.post(() -> {
                                try {
                                    r.triggerModelDownload(i);       // máy tự tải gói tiếng Việt ngoại tuyến
                                } catch (Exception ignored) {
                                }
                                r.destroy();
                            });
                        } else MAIN.post(r::destroy);
                    }

                    @Override
                    public void onError(int error) {
                        viOnDevice = false;
                        MAIN.post(r::destroy);
                    }
                });
            } catch (Exception e) {
                viOnDevice = false;
            }
        });
    }

    private static boolean hasVi(List<String> langs) {
        if (langs == null) return false;
        for (String l : langs) if (l != null && l.toLowerCase().startsWith("vi")) return true;
        return false;
    }

    static String onDeviceStatus() {
        return viOnDevice == null ? "chưa kiểm tra" : viOnDevice ? "có gói tiếng Việt" : "chưa có gói tiếng Việt";
    }

    // ================= Vosk (chạy trên máy, dự phòng) =================

    private void startVosk(String reason) {
        engine = "vosk";
        if (reason != null) cb.onInfo(reason + ", chuyển sang nhận giọng Vosk");
        if (voskModel == null) cb.onState("loading");
        loadVosk(ctx, () -> {
            if (!active) return;
            if (voskModel == null) {
                finish(null, null, "Không nạp được nhận giọng trên máy" + (voskLoadError != null ? ": " + voskLoadError : ""), -2);
                return;
            }
            try {
                Recognizer rec = new Recognizer(voskModel, 16000.0f);
                rec.setMaxAlternatives(3);
                vosk = new SpeechService(rec, 16000.0f);
                vosk.startListening(voskListener);
                cb.onState("listening");
            } catch (Exception e) {
                SpeechHelper.recordError(ctx, "Vosk: mở micro", SpeechHelper.stack(e));
                finish(null, null, "Không mở được micro, thử lại sau giây lát", -3);
            }
        });
    }

    private static void loadVosk(Context ctx, Runnable then) {
        if (voskModel != null) {
            if (then != null) MAIN.post(then);
            return;
        }
        if (then != null) voskWaiters.add(then);
        if (voskLoading) return;
        voskLoading = true;
        voskLoadError = null;
        StorageService.unpack(ctx, "model-vn", "model",
            model -> {
                voskModel = model;
                voskLoading = false;
                runWaiters();
            },
            e -> {
                voskLoading = false;
                voskLoadError = e.getMessage();
                SpeechHelper.recordError(ctx, "Vosk: nạp model", SpeechHelper.stack(e));
                runWaiters();
            });
    }

    private static void runWaiters() {
        List<Runnable> w = new ArrayList<>(voskWaiters);
        voskWaiters.clear();
        for (Runnable r : w) MAIN.post(r);
    }

    static String voskStatus() {
        return voskModel != null ? "đã nạp" : voskLoading ? "đang nạp" : voskLoadError != null ? "lỗi: " + voskLoadError : "chưa nạp";
    }

    private final org.vosk.android.RecognitionListener voskListener = new org.vosk.android.RecognitionListener() {
        @Override
        public void onPartialResult(String hypothesis) {
            try {
                String p = new JSONObject(hypothesis).optString("partial", "").trim();
                if (active && !p.isEmpty()) cb.onPartial(p);
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onResult(String hypothesis) {          // ngắt giọng: lấy câu này làm kết quả
            deliver(hypothesis);
        }

        @Override
        public void onFinalResult(String hypothesis) {     // sau khi Dừng
            if (!deliver(hypothesis) && active) finish(null, null, SpeechHelper.message(SpeechRecognizer.ERROR_NO_MATCH), SpeechRecognizer.ERROR_NO_MATCH);
        }

        @Override
        public void onError(Exception e) {
            SpeechHelper.recordError(ctx, "Vosk", SpeechHelper.stack(e));
            finish(null, null, "Nhận giọng trên máy gặp lỗi", -4);
        }

        @Override
        public void onTimeout() {
            finish(null, null, SpeechHelper.message(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
        }
    };

    /** Lấy câu (và tối đa 3 phương án) từ JSON của Vosk; trả false nếu rỗng. */
    private boolean deliver(String hypothesis) {
        if (!active) return true;
        try {
            JSONObject o = new JSONObject(hypothesis);
            String text = o.optString("text", "").trim();
            List<String> alts = new ArrayList<>();
            JSONArray arr = o.optJSONArray("alternatives");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String t = arr.getJSONObject(i).optString("text", "").trim();
                    if (!t.isEmpty()) alts.add(t);
                }
            }
            if (text.isEmpty() && !alts.isEmpty()) text = alts.remove(0);
            if (text.isEmpty()) return false;
            finish(VoiceNumbers.normalize(text, alts), "vosk", null, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
