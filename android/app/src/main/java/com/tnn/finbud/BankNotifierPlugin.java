package com.tnn.finbud;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Cầu nối JS <-> dịch vụ đọc thông báo.
 * JS: Capacitor.Plugins.BankNotifier.getStatus() / openSettings() / setBankEnabled({pkg,enabled})
 *     / getPending() / ack({ids}) / getSamples() / clearSamples()
 */
@CapacitorPlugin(name = "BankNotifier")
public class BankNotifierPlugin extends Plugin {

    private boolean isListenerEnabled() {
        String flat = Settings.Secure.getString(getContext().getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getContext().getPackageName());
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("enabled", isListenerEnabled());
        JSArray banks = new JSArray();
        for (String[] b : BankStore.BANKS) {
            JSObject o = new JSObject();
            o.put("pkg", b[0]);
            o.put("label", b[1]);
            o.put("enabled", BankStore.isEnabled(getContext(), b[0]));
            banks.put(o);
        }
        ret.put("banks", banks);
        ret.put("samples", BankStore.getSamples(getContext()).length());
        call.resolve(ret);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    /** Trang thông tin ứng dụng: nơi có mục "Cho phép cài đặt bị hạn chế" (menu ba chấm) trên Android 13+. */
    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void setBankEnabled(PluginCall call) {
        String pkg = call.getString("pkg");
        Boolean enabled = call.getBoolean("enabled", false);
        if (pkg == null) {
            call.reject("Thiếu tên gói");
            return;
        }
        BankStore.setEnabled(getContext(), pkg, enabled != null && enabled);
        call.resolve();
    }

    @PluginMethod
    public void getPending(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("items", BankStore.getPending(getContext()));
        call.resolve(ret);
    }

    @PluginMethod
    public void ack(PluginCall call) {
        BankStore.ack(getContext(), call.getArray("ids"));
        call.resolve();
    }

    @PluginMethod
    public void getSamples(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("items", BankStore.getSamples(getContext()));
        call.resolve(ret);
    }

    @PluginMethod
    public void clearSamples(PluginCall call) {
        BankStore.clearSamples(getContext());
        call.resolve();
    }
}
