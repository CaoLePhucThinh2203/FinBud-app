package com.tnn.finbud;

import android.Manifest;
import android.content.Context;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

/**
 * Nút Nói trong app: bọc VoiceEngine (cùng hệ thống với pop-up nhập nhanh và nút Nói trên widget).
 *
 * JS:
 *   FinBudVoice.requestPermissions()           -> { audio: "granted" | ... }
 *   FinBudVoice.start({ deviceOnly })          deviceOnly = cài đặt "Chỉ trên máy"
 *   FinBudVoice.stop()                         dừng nghe, vẫn trả kết quả phần đã nói
 *   FinBudVoice.cancel()                       huỷ, không trả kết quả
 *   FinBudVoice.prepare({ deviceOnly })        nạp trước (Vosk, kiểm tra gói tiếng Việt)
 *   FinBudVoice.diagnostics() / clearDiagnostics()
 * Sự kiện: "state" {state}, "partial" {text}, "result" {text, engine}, "info" {message}, "error" {message, code}
 *   text của "result" đã đổi số đọc bằng chữ thành chữ số, ví dụ "ăn bún bò 35.000đ".
 */
@CapacitorPlugin(
    name = "FinBudVoice",
    permissions = {@Permission(strings = {Manifest.permission.RECORD_AUDIO}, alias = "audio")}
)
public class VoicePlugin extends Plugin {

    private VoiceEngine engine;

    @Override
    public void load() {
        SpeechHelper.installCrashLog(getContext(), "app");
        engine = new VoiceEngine(getContext(), new VoiceEngine.Callback() {
            public void onState(String state) {
                emit("state", "state", state);
            }

            public void onPartial(String text) {
                emit("partial", "text", text);
            }

            public void onResult(String text, String eng) {
                JSObject o = new JSObject();
                o.put("text", text);
                o.put("engine", eng);
                notifyListeners("result", o);
            }

            public void onInfo(String message) {
                emit("info", "message", message);
            }

            public void onError(String message, int code) {
                JSObject o = new JSObject();
                o.put("message", message);
                o.put("code", code);
                notifyListeners("error", o);
            }
        });
    }

    private void emit(String event, String key, String value) {
        JSObject o = new JSObject();
        o.put(key, value);
        notifyListeners(event, o);
    }

    @PluginMethod
    public void start(PluginCall call) {
        boolean deviceOnly = Boolean.TRUE.equals(call.getBoolean("deviceOnly", false));
        getActivity().runOnUiThread(() -> {
            engine.start(deviceOnly);
            call.resolve();
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            engine.stop();
            call.resolve();
        });
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            engine.release();
            call.resolve();
        });
    }

    @PluginMethod
    public void prepare(PluginCall call) {
        boolean deviceOnly = Boolean.TRUE.equals(call.getBoolean("deviceOnly", false));
        getActivity().runOnUiThread(() -> {
            VoiceEngine.prepare(getContext(), deviceOnly);
            call.resolve();
        });
    }

    @PluginMethod
    public void diagnostics(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(SpeechHelper.diagnostics(getContext())));
        } catch (Exception e) {
            call.resolve(new JSObject());
        }
    }

    @PluginMethod
    public void clearDiagnostics(PluginCall call) {
        getContext().getSharedPreferences(FinBudWidget.PREFS, Context.MODE_PRIVATE)
            .edit().remove(SpeechHelper.KEY_LAST_ERROR).apply();
        call.resolve();
    }

    @Override
    protected void handleOnPause() {
        if (engine != null) engine.release();                 // rời app thì nhả micro
    }

    @Override
    protected void handleOnDestroy() {
        if (engine != null) engine.release();
    }
}
