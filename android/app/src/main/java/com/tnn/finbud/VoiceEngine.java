package com.tnn.finbud;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * MỘT hệ thống nhận giọng dùng chung cho cả 3 nút Nói: nút Nói trên widget, nút Nói trong pop-up nhập nhanh
 * (QuickAddActivity) và nút Nói trong app (qua VoicePlugin). Cả hai bộ nhận giọng đều chạy trên máy,
 * âm thanh không rời khỏi điện thoại:
 *
 *   "zip"  – Mô hình mới: Zipformer tiếng Việt 30M (sherpa-onnx, assets/zipformer-vi). Chính xác hơn nhiều.
 *            Nạp không được thì tự chuyển sang Vosk.
 *   "vosk" – Mô hình cũ: Vosk (assets/model-vn).
 *
 * Kết quả luôn đã đổi số đọc bằng chữ thành chữ số (VoiceNumbers), ví dụ "ăn bún bò 35.000đ".
 * Ghi âm do app tự làm (AudioRecord, luồng riêng); mọi lỗi micro được bắt và báo thành thông báo, không văng app.
 * Mọi hàm (trừ luồng ghi âm) chạy trên luồng giao diện. Người gọi phải xin quyền RECORD_AUDIO trước khi start().
 */
final class VoiceEngine {

    static final String ZIP = "zip", VOSK = "vosk";

    interface Callback {
        void onState(String state);                  // "loading" (đang nạp mô hình) | "listening" | "idle"
        void onPartial(String text);                 // chữ tạm khi đang nói
        void onResult(String text, String engine);   // text đã đổi số; engine = "zip" | "vosk"
        void onInfo(String message);                 // thông báo phụ, ví dụ phải chuyển sang Vosk
        void onError(String message, int code);      // kết thúc mà không có kết quả
    }

    static final int NO_MATCH = 7, NO_PERMISSION = 9;
    private static final int RATE = 16000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---- Mô hình: nạp một lần cho cả process (app và pop-up dùng chung) ----
    private static Model voskModel;
    private static boolean voskLoading;
    private static String voskLoadError;
    private static final List<Runnable> voskWaiters = new ArrayList<>();

    private static OfflineRecognizer zipModel;
    private static boolean zipLoading;
    private static String zipLoadError;
    private static final List<Runnable> zipWaiters = new ArrayList<>();

    private final Context ctx;
    private final Callback cb;
    private Loop loop;              // luồng ghi âm đang chạy
    private boolean active;         // đang trong một lượt nghe (chưa trả kết quả / lỗi)

    VoiceEngine(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    /** "vosk" giữ nguyên; mọi giá trị khác (kể cả "best", "device" của bản cũ) -> mô hình mới. */
    static String normalizeEngine(String e) {
        return VOSK.equals(e) ? VOSK : ZIP;
    }

    // ================= Bắt đầu / dừng =================

    void start(String engine) {
        release();
        active = true;
        if (VOSK.equals(normalizeEngine(engine))) startVosk();
        else startZip();
    }

    /** Dừng nghe; phần đã nói vẫn được nhận dạng và trả về qua onResult. */
    void stop() {
        if (!active) return;
        if (loop != null) loop.run = false;                 // luồng ghi âm nhận dạng nốt phần đã nói rồi báo về
        else finish(null, null, null, -1);                  // đang nạp mô hình: dừng luôn, không báo lỗi
    }

    /** Huỷ hẳn, không trả kết quả (đóng pop-up, rời màn hình). */
    void release() {
        active = false;
        if (loop != null) {
            loop.cancelled = true;
            loop.run = false;
            loop = null;
        }
    }

    /** Nạp trước mô hình sẽ dùng, để lần bấm Nói đầu tiên không phải chờ. */
    static void prepare(Context ctx, String engine) {
        Context app = ctx.getApplicationContext();
        if (VOSK.equals(normalizeEngine(engine))) loadVosk(app, null);
        else loadZip(app, null);
    }

    private void finish(String text, String eng, String err, int code) {
        if (!active) return;
        active = false;
        if (loop != null) {
            loop.run = false;
            loop = null;
        }
        cb.onState("idle");
        if (text != null && !text.trim().isEmpty()) cb.onResult(text.trim(), eng);
        else if (err != null) cb.onError(err, code);
    }

    // ================= Mô hình mới: Zipformer (sherpa-onnx) =================

    private void startZip() {
        if (zipModel == null) cb.onState("loading");
        loadZip(ctx, () -> {
            if (!active) return;
            if (zipModel == null) {                          // không nạp được: vẫn nói được bằng Vosk
                cb.onInfo("Không nạp được Zipformer 30M, chuyển sang Vosk");
                startVosk();
                return;
            }
            beginRecording(ZIP);
        });
    }

    private static void loadZip(Context ctx, Runnable then) {
        if (zipModel != null) {
            if (then != null) MAIN.post(then);
            return;
        }
        if (then != null) zipWaiters.add(then);
        if (zipLoading) return;
        zipLoading = true;
        zipLoadError = null;
        Executors.newSingleThreadExecutor().execute(() -> {
            OfflineRecognizer r = null;
            String err = null;
            try {
                OfflineTransducerModelConfig t = new OfflineTransducerModelConfig();
                t.setEncoder("zipformer-vi/encoder.int8.onnx");
                t.setDecoder("zipformer-vi/decoder.onnx");
                t.setJoiner("zipformer-vi/joiner.int8.onnx");
                OfflineModelConfig m = new OfflineModelConfig();
                m.setTransducer(t);
                m.setTokens("zipformer-vi/tokens.txt");
                m.setNumThreads(2);
                FeatureConfig f = new FeatureConfig();
                f.setSampleRate(RATE);
                f.setFeatureDim(80);
                OfflineRecognizerConfig c = new OfflineRecognizerConfig();
                c.setFeatConfig(f);
                c.setModelConfig(m);
                c.setDecodingMethod("greedy_search");
                r = new OfflineRecognizer(ctx.getAssets(), c);   // đọc thẳng từ assets của APK
            } catch (Throwable e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
                SpeechHelper.recordError(ctx, "Mô hình mới: nạp", SpeechHelper.stack(e));
            }
            final OfflineRecognizer fr = r;
            final String fe = err;
            MAIN.post(() -> {
                zipModel = fr;
                zipLoadError = fe;
                zipLoading = false;
                runWaiters(zipWaiters);
            });
        });
    }

    static String zipStatus() {
        return zipModel != null ? "đã nạp" : zipLoading ? "đang nạp" : zipLoadError != null ? "lỗi: " + zipLoadError : "chưa nạp";
    }

    private static String zipDecode(float[] samples, int n) {
        OfflineStream s = zipModel.createStream();
        try {
            s.acceptWaveform(n == samples.length ? samples : Arrays.copyOf(samples, n), RATE);
            zipModel.decode(s);
            return zipModel.getResult(s).getText().trim().toLowerCase();
        } finally {
            s.release();
        }
    }

    // ================= Mô hình cũ: Vosk =================

    private void startVosk() {
        if (voskModel == null) cb.onState("loading");
        loadVosk(ctx, () -> {
            if (!active) return;
            if (voskModel == null) {
                finish(null, null, "Không nạp được nhận giọng Vosk" + (voskLoadError != null ? ": " + voskLoadError : ""), -2);
                return;
            }
            beginRecording(VOSK);
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
                runWaiters(voskWaiters);
            },
            e -> {
                voskLoading = false;
                voskLoadError = e.getMessage();
                SpeechHelper.recordError(ctx, "Vosk: nạp model", SpeechHelper.stack(e));
                runWaiters(voskWaiters);
            });
    }

    private static void runWaiters(List<Runnable> waiters) {
        List<Runnable> w = new ArrayList<>(waiters);
        waiters.clear();
        for (Runnable r : w) MAIN.post(r);
    }

    static String voskStatus() {
        return voskModel != null ? "đã nạp" : voskLoading ? "đang nạp" : voskLoadError != null ? "lỗi: " + voskLoadError : "chưa nạp";
    }

    // ================= Ghi âm dùng chung =================

    private void beginRecording(String engine) {
        if (!active) return;
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finish(null, null, "Cần cấp quyền micro để nói", NO_PERMISSION);
            return;
        }
        AudioRecord ar = null;
        Recognizer rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2 * 4));      // đệm 4 giây: lúc nhận dạng tạm không mất âm thanh
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord chưa khởi tạo được");
            if (VOSK.equals(engine)) {
                rec = new Recognizer(voskModel, RATE);
                rec.setMaxAlternatives(3);
            }
            ar.startRecording();
            if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("micro không ghi âm được");
            loop = new Loop(engine, ar, rec);
            loop.start();
            cb.onState("listening");
        } catch (Throwable e) {
            SpeechHelper.recordError(ctx, "Mở micro", SpeechHelper.stack(e));
            if (ar != null) try { ar.release(); } catch (Throwable ignored) { }
            if (rec != null) try { rec.close(); } catch (Throwable ignored) { }
            finish(null, null, "Không mở được micro (có thể ứng dụng khác đang dùng), thử lại sau giây lát", -3);
        }
    }

    /**
     * Luồng ghi âm + nhận dạng. Tự dừng khi ngừng nói ~0,9 giây, quá 12 giây, hoặc 7 giây không nghe thấy gì.
     * Zipformer: gom âm thanh, cứ ~1,2 giây nhận dạng tạm để hiện chữ, cuối cùng nhận dạng cả câu.
     * Vosk: nhận dạng liên tục. Chính luồng này giải phóng micro và Recognizer khi đã dừng hẳn.
     */
    private final class Loop extends Thread {
        final String engine;
        private final AudioRecord ar;
        private final Recognizer rec;          // chỉ dùng cho Vosk
        volatile boolean run = true, cancelled;

        Loop(String engine, AudioRecord ar, Recognizer rec) {
            super("FinBudVoice");
            this.engine = engine;
            this.ar = ar;
            this.rec = rec;
        }

        @Override
        public void run() {
            short[] buf = new short[RATE / 10];                          // 0,1 giây mỗi lần đọc
            float[] all = VOSK.equals(engine) ? null : new float[RATE * 13];   // tối đa ~13 giây cho Zipformer
            int len = 0, chunks = 0;
            double noise = 0;
            boolean heard = false;
            long t0 = SystemClock.uptimeMillis(), lastVoice = 0, lastPartial = 0;
            String out = null, err = null;
            try {
                while (run) {
                    int n = ar.read(buf, 0, buf.length);
                    if (n < 0) throw new IllegalStateException("đọc micro lỗi (mã " + n + ")");
                    if (n == 0) continue;
                    long now = SystemClock.uptimeMillis();

                    // Phát hiện đang nói theo âm lượng; 3 khối đầu (0,3 giây) dùng để đo tiếng ồn nền
                    double rms = rms(buf, n);
                    if (chunks < 3) noise += rms / 3;
                    chunks++;
                    if (chunks > 3 && rms > Math.max(noise * 2.5, 400)) {
                        heard = true;
                        lastVoice = now;
                    }

                    if (all == null) {                                    // Vosk
                        if (rec.acceptWaveForm(buf, n)) {
                            String r = voskText(rec.getResult());
                            if (!r.isEmpty()) {
                                out = r;
                                break;
                            }
                        } else {
                            String p = partialText(rec.getPartialResult());
                            if (!p.isEmpty()) {
                                heard = true;
                                lastVoice = Math.max(lastVoice, now - 300);
                                post(p);
                            }
                        }
                    } else {                                              // Zipformer
                        int m = Math.min(n, all.length - len);
                        for (int i = 0; i < m; i++) all[len + i] = buf[i] / 32768f;
                        len += m;
                        if (heard && now - lastPartial > 1200) {
                            lastPartial = now;
                            String p = zipDecode(all, len);
                            if (!p.isEmpty()) post(p);
                        }
                        if (len >= all.length) break;
                    }

                    long el = now - t0;
                    if (heard && now - lastVoice > 900) break;               // đã ngừng nói
                    if (el > 12000 || (!heard && el > 7000)) break;
                }
                if (!cancelled) {
                    if (all == null) {
                        if (out == null) out = voskText(rec.getFinalResult());
                    } else if (heard && len > RATE / 4) {
                        out = VoiceNumbers.normalize(zipDecode(all, len), null);
                    }
                }
            } catch (Throwable e) {
                err = SpeechHelper.stack(e);
            } finally {
                try { ar.stop(); } catch (Throwable ignored) { }
                try { ar.release(); } catch (Throwable ignored) { }
                if (rec != null) try { rec.close(); } catch (Throwable ignored) { }
            }
            final String fOut = out, fErr = err;
            MAIN.post(() -> ended(this, fOut, fErr));
        }

        private void post(String partial) {
            MAIN.post(() -> { if (loop == this && active) cb.onPartial(partial); });
        }
    }

    private void ended(Loop l, String out, String err) {
        if (loop != l || l.cancelled) return;                   // đã huỷ / đã có lượt mới
        loop = null;
        if (!active) return;
        if (err != null) {
            SpeechHelper.recordError(ctx, (VOSK.equals(l.engine) ? "Vosk" : "Mô hình mới") + ": ghi âm / nhận dạng", err);
            finish(null, null, "Micro bị gián đoạn, bạn thử lại nhé", -5);
        } else if (out == null || out.trim().isEmpty()) {
            finish(null, null, "Không nghe rõ, bạn thử lại nhé", NO_MATCH);
        } else {
            finish(out, l.engine, null, 0);
        }
    }

    private static double rms(short[] b, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) s += (double) b[i] * b[i];
        return Math.sqrt(s / Math.max(n, 1));
    }

    /** Câu (và tối đa 3 phương án) từ JSON của Vosk, đã chọn phương án tốt nhất và đổi số; "" nếu rỗng. */
    private static String voskText(String json) {
        try {
            JSONObject o = new JSONObject(json);
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
            return text.isEmpty() ? "" : VoiceNumbers.normalize(text, alts);
        } catch (Exception e) {
            return "";
        }
    }

    private static String partialText(String json) {
        try {
            return new JSONObject(json).optString("partial", "").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
