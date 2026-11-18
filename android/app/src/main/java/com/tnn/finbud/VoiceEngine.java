package com.tnn.finbud;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.SpeechRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
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
    private VoskLoop loop;                                   // luồng ghi âm của Vosk đang chạy
    private List<android.content.ComponentName> services;   // các dịch vụ trực tuyến sẽ thử lần lượt (null = mặc định)
    private int attempt;                                     // đang thử dịch vụ thứ mấy
    private static int okService = -1;                       // dịch vụ đã chạy được lần trước, lần sau dùng luôn
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
            services = SpeechHelper.candidates(ctx);
            attempt = okService >= 0 && okService < services.size() ? okService : 0;
            if (!services.isEmpty()) startAndroid(false);
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
        else if (loop != null) loop.run = false;             // luồng Vosk lấy nốt kết quả phần đã nói rồi báo về
        else finish(null, null, null, -1);                   // đang nạp model: dừng luôn, không báo lỗi
    }

    /** Huỷ hẳn, không trả kết quả (đóng pop-up, rời màn hình). */
    void release() {
        active = false;
        MAIN.removeCallbacksAndMessages(this);
        destroyRecognizer();
        closeVosk();
    }

    /** Báo luồng ghi âm Vosk dừng; chính luồng đó giải phóng micro và Recognizer khi đã dừng hẳn. */
    private void closeVosk() {
        if (loop != null) {
            loop.run = false;
            loop = null;
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
        closeVosk();
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
            recognizer = onDevice ? SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                : SpeechHelper.create(ctx, services.get(attempt));
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) {
                    if (!onDevice) okService = attempt;           // dịch vụ này chạy được
                }
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
        // Mã 5 / 11 / 12 / 13: dịch vụ này không dùng được (thường do từ chối app khác hoặc không có tiếng Việt).
        // Thử dịch vụ kế tiếp (Google khác, rồi dịch vụ mặc định của máy) trước khi chuyển sang Vosk.
        if (!onDevice && (code == SpeechRecognizer.ERROR_CLIENT || code == 11 || code == 12 || code == 13)
            && services != null && attempt + 1 < services.size()) {
            if (okService == attempt) okService = -1;
            attempt++;
            MAIN.postAtTime(() -> { if (active) startAndroid(false); }, this, android.os.SystemClock.uptimeMillis() + 200);
            return;
        }
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
    // Tự ghi âm bằng AudioRecord thay cho SpeechService của thư viện Vosk: luồng ghi âm của thư viện ném lỗi
    // ("error reading audio buffer") ở luồng nền khi micro bị gián đoạn -> văng app. Ở đây mọi lỗi đều được bắt
    // và báo thành thông báo; Recognizer chỉ được giải phóng bởi chính luồng ghi âm sau khi nó dừng hẳn.

    private void startVosk(String reason) {
        engine = "vosk";
        boolean afterAndroid = reason != null;
        if (afterAndroid) cb.onInfo(reason + ", chuyển sang nhận giọng Vosk");
        if (voskModel == null) cb.onState("loading");
        loadVosk(ctx, () -> {
            if (!active) return;
            if (voskModel == null) {
                finish(null, null, "Không nạp được nhận giọng trên máy" + (voskLoadError != null ? ": " + voskLoadError : ""), -2);
                return;
            }
            // Vừa thử nhận giọng của Android: chờ một chút để dịch vụ đó nhả micro hẳn
            MAIN.postAtTime(this::beginVosk, this, SystemClock.uptimeMillis() + (afterAndroid ? 400 : 0));
        });
    }

    private void beginVosk() {
        if (!active) return;
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finish(null, null, SpeechHelper.message(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS), SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            return;
        }
        AudioRecord ar = null;
        Recognizer rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));            // đệm ≥ 0,5 giây
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord chưa khởi tạo được");
            rec = new Recognizer(voskModel, RATE);
            rec.setMaxAlternatives(3);
            ar.startRecording();
            if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("micro không ghi âm được");
            loop = new VoskLoop(ar, rec);
            loop.start();
            cb.onState("listening");
        } catch (Throwable e) {
            SpeechHelper.recordError(ctx, "Vosk: mở micro", SpeechHelper.stack(e));
            if (ar != null) try { ar.release(); } catch (Throwable ignored) { }
            if (rec != null) try { rec.close(); } catch (Throwable ignored) { }
            finish(null, null, "Không mở được micro (có thể ứng dụng khác đang dùng), thử lại sau giây lát", -3);
        }
    }

    private static final int RATE = 16000;

    /** Luồng ghi âm + nhận dạng của Vosk. Tự dừng khi ngắt giọng, quá 15 giây, hoặc 8 giây không nghe thấy gì. */
    private final class VoskLoop extends Thread {
        private final AudioRecord ar;
        private final Recognizer rec;
        volatile boolean run = true;

        VoskLoop(AudioRecord ar, Recognizer rec) {
            super("FinBudVosk");
            this.ar = ar;
            this.rec = rec;
        }

        @Override
        public void run() {
            short[] buf = new short[RATE / 10];                         // 0,1 giây mỗi lần đọc
            long t0 = SystemClock.uptimeMillis();
            boolean heard = false;
            String out = null, err = null;
            try {
                while (run) {
                    int n = ar.read(buf, 0, buf.length);
                    if (n < 0) throw new IllegalStateException("đọc micro lỗi (mã " + n + ")");
                    if (n == 0) continue;
                    if (rec.acceptWaveForm(buf, n)) {
                        String r = rec.getResult();
                        if (hasText(r)) {
                            out = r;
                            break;
                        }
                    } else {
                        String p = partialText(rec.getPartialResult());
                        if (!p.isEmpty()) {
                            heard = true;
                            MAIN.post(() -> { if (loop == this && active) cb.onPartial(p); });
                        }
                    }
                    long el = SystemClock.uptimeMillis() - t0;
                    if (el > 15000 || (!heard && el > 8000)) break;
                }
                if (out == null) out = rec.getFinalResult();
            } catch (Throwable e) {
                err = SpeechHelper.stack(e);
            } finally {
                try { ar.stop(); } catch (Throwable ignored) { }
                try { ar.release(); } catch (Throwable ignored) { }
                try { rec.close(); } catch (Throwable ignored) { }
            }
            final String fOut = out, fErr = err;
            MAIN.post(() -> loopEnded(this, fOut, fErr));
        }
    }

    private void loopEnded(VoskLoop l, String out, String err) {
        if (loop != l) return;                                         // đã huỷ / đã có lượt mới
        loop = null;
        if (!active) return;
        if (err != null) {
            SpeechHelper.recordError(ctx, "Vosk: ghi âm", err);
            finish(null, null, "Micro bị gián đoạn, bạn thử lại nhé", -5);
        } else if (out == null || !deliver(out)) {
            finish(null, null, SpeechHelper.message(SpeechRecognizer.ERROR_NO_MATCH), SpeechRecognizer.ERROR_NO_MATCH);
        }
    }

    private static boolean hasText(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!o.optString("text", "").trim().isEmpty()) return true;
            JSONArray a = o.optJSONArray("alternatives");
            if (a != null) for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).optString("text", "").trim().isEmpty()) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String partialText(String json) {
        try {
            return new JSONObject(json).optString("partial", "").trim();
        } catch (Exception e) {
            return "";
        }
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
