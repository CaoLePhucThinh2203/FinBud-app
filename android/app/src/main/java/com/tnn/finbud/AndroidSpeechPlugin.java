package com.tnn.finbud;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

import java.util.ArrayList;

/**
 * Nhận giọng nói bằng dịch vụ có sẵn của Android (thường là Google), dùng cho ô "Nói / gõ" trong app.
 * Chính xác hơn Vosk nhiều, nhưng mặc định âm thanh được gửi lên máy chủ của dịch vụ đó.
 *
 * JS:
 *   AndroidSpeech.isAvailable()            -> { available, onDevice }
 *   AndroidSpeech.start({ offline })       offline = true: chỉ nhận trên máy (cần gói tiếng Việt ngoại tuyến)
 *   AndroidSpeech.stop()
 *   addListener("partial", {text}) / ("result", {text, alternatives}) / ("error", {code, message, fallback})
 *   fallback = true nghĩa là nên chuyển sang Vosk (mất mạng, chưa có gói ngôn ngữ, dịch vụ lỗi).
 */
@CapacitorPlugin(
    name = "AndroidSpeech",
    permissions = {@Permission(strings = {Manifest.permission.RECORD_AUDIO}, alias = "audio")}
)
public class AndroidSpeechPlugin extends Plugin {

    private SpeechRecognizer recognizer;

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject o = new JSObject();
        o.put("available", SpeechRecognizer.isRecognitionAvailable(getContext()));
        o.put("onDevice", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && SpeechRecognizer.isOnDeviceRecognitionAvailable(getContext()));
        call.resolve(o);
    }

    @PluginMethod
    public void start(PluginCall call) {
        boolean offline = Boolean.TRUE.equals(call.getBoolean("offline", false));
        getActivity().runOnUiThread(() -> {               // SpeechRecognizer phải chạy trên luồng giao diện
            destroy();
            boolean onDevice = offline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(getContext());
            recognizer = onDevice ? SpeechRecognizer.createOnDeviceSpeechRecognizer(getContext())
                : SpeechRecognizer.createSpeechRecognizer(getContext());
            recognizer.setRecognitionListener(listener);
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            if (offline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            recognizer.startListening(i);
            call.resolve();
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (recognizer != null) recognizer.stopListening();
            call.resolve();
        });
    }

    private void destroy() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    @Override
    protected void handleOnDestroy() {
        destroy();
    }

    private final RecognitionListener listener = new RecognitionListener() {
        public void onReadyForSpeech(Bundle b) {}
        public void onBeginningOfSpeech() {}
        public void onRmsChanged(float v) {}
        public void onBufferReceived(byte[] b) {}
        public void onEndOfSpeech() {}
        public void onEvent(int t, Bundle b) {}

        public void onPartialResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty() || r.get(0).trim().isEmpty()) return;
            JSObject o = new JSObject();
            o.put("text", r.get(0));
            notifyListeners("partial", o);
        }

        public void onResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            JSObject o = new JSObject();
            JSArray alts = new JSArray();
            if (r != null) for (String s : r) alts.put(s);
            o.put("text", r != null && !r.isEmpty() ? r.get(0) : "");
            o.put("alternatives", alts);
            notifyListeners("result", o);
        }

        public void onError(int code) {
            JSObject o = new JSObject();
            o.put("code", code);
            o.put("message", message(code));
            o.put("fallback", shouldFallback(code));
            notifyListeners("error", o);
        }
    };

    /** Lỗi do mạng / gói ngôn ngữ / dịch vụ: nên chuyển sang Vosk. Lỗi "không nghe rõ" thì cho người dùng nói lại. */
    private static boolean shouldFallback(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
            case SpeechRecognizer.ERROR_CLIENT:
            case 11:  // ERROR_SERVER_DISCONNECTED (Android 12+)
            case 12:  // ERROR_LANGUAGE_NOT_SUPPORTED (Android 12+)
            case 13:  // ERROR_LANGUAGE_UNAVAILABLE (Android 12+)
                return true;
            default:
                return false;
        }
    }

    private static String message(int code) {
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
                return "Không có mạng";
            case 12:
            case 13:
                return "Máy chưa có gói tiếng Việt ngoại tuyến";
            default:
                return "Nhận giọng nói gặp lỗi (" + code + ")";
        }
    }
}
