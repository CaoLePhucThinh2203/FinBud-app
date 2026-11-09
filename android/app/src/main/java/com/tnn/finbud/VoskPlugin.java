package com.tnn.finbud;

import android.Manifest;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

import org.json.JSONArray;
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
        @Permission(strings = {Manifest.permission.RECORD_AUDIO}, alias = "audio")
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

        // Giải nén model từ assets (thư mục assets/model-vn, có file uuid) vào bộ nhớ máy ở lần đầu mở app
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

    /**
     * JS: VoskVoice.startListening({ grammar: ["một","hai",...,"[unk]"] })
     * grammar là tuỳ chọn: giới hạn từ vựng để nhận chính xác hơn với câu ngắn (từ ngoài danh sách bị bỏ qua / thành [unk]).
     */
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
            JSArray grammar = call.getArray("grammar");
            Recognizer rec;
            if (grammar != null && grammar.length() > 0) {
                rec = new Recognizer(model, 16000.0f, grammar.toString());
            } else {
                rec = new Recognizer(model, 16000.0f);
            }
            rec.setMaxAlternatives(3);   // trả tối đa 3 phương án nghe để JS chọn phương án có số tiền hợp lệ
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

    // Kết quả tạm thời khi đang nói: gửi lên để người dùng thấy máy đang nghe gì
    @Override
    public void onPartialResult(String hypothesis) {
        try {
            String partial = new JSONObject(hypothesis).optString("partial", "").trim();
            if (!partial.isEmpty()) {
                JSObject data = new JSObject();
                data.put("text", partial);
                data.put("isFinal", false);
                notifyListeners("onVoiceResult", data);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // Một câu hoàn chỉnh sau khi ngắt giọng -> coi là kết quả cuối
    @Override
    public void onResult(String hypothesis) {
        sendResultToWeb(hypothesis, true);
    }

    // Khi dừng hẳn thu âm (thường rỗng vì onResult đã gửi rồi)
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
            JSArray alternatives = new JSArray();
            // Khi bật setMaxAlternatives, Vosk trả {"alternatives":[{"text":"...","confidence":...}, ...]}
            JSONArray arr = obj.optJSONArray("alternatives");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String t = arr.getJSONObject(i).optString("text", "").trim();
                    if (!t.isEmpty()) {
                        alternatives.put(t);
                        if (text.isEmpty()) {
                            text = t;
                        }
                    }
                }
            }
            if (text.isEmpty()) {
                return;
            }
            JSObject data = new JSObject();
            data.put("text", text);
            data.put("alternatives", alternatives);
            data.put("isFinal", isFinal);
            notifyListeners("onVoiceResult", data);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
