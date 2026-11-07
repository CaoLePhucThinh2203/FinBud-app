package com.tnn.finbud;

import android.Manifest;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;

@CapacitorPlugin(
    name = "VoskVoice",
    permissions = {
        @Permission(strings = {Manifest.permission.RECORD_AUDIO}, name = "audio")
    }
)
public class VoskPlugin extends Plugin implements RecognitionListener {

    private Model model;
    private SpeechService speechService;
    private boolean isModelLoaded = false;

    @PluginMethod
    public void initModel(PluginCall call) {
        if (isModelLoaded) {
            call.resolve();
            return;
        }

        // Giải nén model từ assets vào bộ nhớ máy ở lần đầu mở app
        StorageService.unpack(getContext(), "model-vn", "model",
            (loadedModel) -> {
                this.model = loadedModel;
                this.isModelLoaded = true;
                JSObject ret = new JSObject();
                ret.put("status", "ready");
                call.resolve(ret);
            },
            (exception) -> {
                call.reject("Lỗi nạp model Vosk: " + exception.getMessage());
            }
        );
    }

    @PluginMethod
    public void startListening(PluginCall call) {
        if (!isModelLoaded) {
            call.reject("Model chưa sẵn sàng, vui lòng đợi vài giây.");
            return;
        }

        if (speechService != null) {
            speechService.stop();
            speechService = null;
        }

        try {
            Recognizer rec = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(rec, 16000.0f);
            speechService.startListening(this);
            call.resolve();
        } catch (IOException e) {
            call.reject("Không thể bật micro: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopListening(PluginCall call) {
        if (speechService != null) {
            speechService.stop();
            speechService = null;
        }
        call.resolve();
    }

    // Callback trả kết quả từng từ khi người dùng đang nói
    @Override
    public void onPartialResult(String hypothesis) {}

    // Callback trả về câu hoàn chỉnh sau khi ngắt giọng
    @Override
    public void onResult(String hypothesis) {
        sendResultToWeb(hypothesis, false);
    }

    // Callback khi dừng hẳn thu âm
    @Override
    public void onFinalResult(String hypothesis) {
        sendResultToWeb(hypothesis, true);
    }

    @Override
    public void onError(Exception exception) {
        JSObject error = new JSObject();
        error.put("error", exception.getMessage());
        notifyListeners("onError", error);
    }

    @Override
    public void onTimeout() {}

    private void sendResultToWeb(String hypothesis, boolean isFinal) {
        try {
            JSONObject obj = new JSONObject(hypothesis);
            String text = obj.optString("text", "").trim();
            if (!text.isEmpty()) {
                JSObject data = new JSObject();
                data.put("text", text);
                data.put("isFinal", isFinal);
                notifyListeners("onVoiceResult", data);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}