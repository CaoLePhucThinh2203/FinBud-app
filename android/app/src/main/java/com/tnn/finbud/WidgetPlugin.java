package com.tnn.finbud;

import android.content.Context;
import android.content.Intent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Cầu nối giữa app (JS) và widget màn hình chính (FinBudWidget).
 * JS gọi: Capacitor.Plugins.FinBudWidget.update({ data: "<JSON>" })  sau mỗi lần lưu dữ liệu.
 * JS nghe: addListener("widgetAction", e => ...)  với e.action = "home" | "quick" | "ocr"
 *          khi người dùng bấm vào widget (kể cả lúc app đang tắt hẳn).
 */
@CapacitorPlugin(name = "FinBudWidget")
public class WidgetPlugin extends Plugin {

    @PluginMethod
    public void update(PluginCall call) {
        String data = call.getString("data");
        if (data == null) {
            call.reject("Thiếu data");
            return;
        }
        getContext().getSharedPreferences(FinBudWidget.PREFS, Context.MODE_PRIVATE)
            .edit().putString(FinBudWidget.KEY_DATA, data).apply();
        FinBudWidget.updateAll(getContext());
        call.resolve();
    }

    /** Bridge gọi hàm này cả khi app mở lần đầu từ widget lẫn khi app đang chạy nền. */
    @Override
    protected void handleOnNewIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra(FinBudWidget.EXTRA_ACTION);
        if (action == null) return;
        intent.removeExtra(FinBudWidget.EXTRA_ACTION);        // tránh mở lại bảng khi xoay màn hình / quay lại app
        JSObject o = new JSObject();
        o.put("action", action);
        notifyListeners("widgetAction", o, true);              // giữ lại nếu JS chưa kịp đăng ký listener
    }
}
