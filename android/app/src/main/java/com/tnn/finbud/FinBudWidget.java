package com.tnn.finbud;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Iterator;
import java.util.Locale;

/**
 * Widget màn hình chính: hiện "Hôm nay bạn có thể chi", giống khối hạn mức ở trang chủ app.
 *
 * App (index.html) gửi dữ liệu thô qua WidgetPlugin: ngân sách, chi phí cố định, tiền thêm theo tháng,
 * tổng chi theo ngày. Phần tính hạn mức chạy lại ở đây mỗi lần widget cập nhật, nên sang ngày mới
 * con số vẫn đúng dù chưa mở app. Công thức PHẢI giống calc() trong www/index.html.
 */
public class FinBudWidget extends AppWidgetProvider {

    static final String PREFS = "finbud_widget";
    static final String KEY_DATA = "data";
    static final String KEY_PENDING = "pending";            // giao dịch nhập từ bảng nhập nhanh, chờ app lấy vào Lịch sử
    static final String EXTRA_ACTION = "finbud_widget_action";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager manager, int[] ids) {
        RemoteViews views = build(ctx);
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    /** Vẽ lại mọi widget đang đặt trên màn hình (gọi sau khi app lưu dữ liệu mới). */
    static void updateAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, FinBudWidget.class));
        if (ids.length == 0) return;
        RemoteViews views = build(ctx);
        for (int id : ids) m.updateAppWidget(id, views);
    }

    static RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_finbud);
        v.setOnClickPendingIntent(R.id.widget_card, open(ctx, "home", 0));
        v.setOnClickPendingIntent(R.id.widget_btn_voice, quickAdd(ctx, true));
        v.setOnClickPendingIntent(R.id.widget_btn_type, quickAdd(ctx, false));
        v.setOnClickPendingIntent(R.id.widget_btn_ocr, open(ctx, "ocr", 2));

        Limit l = null;
        try {
            JSONObject d = readData(ctx);
            if (d != null) l = compute(withPending(d, readPending(ctx)), Calendar.getInstance());
        } catch (Exception ignored) {
        }

        if (l == null) {                                   // chưa thiết lập ngân sách / chưa mở app lần nào
            v.setInt(R.id.widget_card, "setBackgroundResource", R.drawable.widget_bg_ok);
            v.setTextViewText(R.id.widget_amount, "Chưa thiết lập");
            v.setProgressBar(R.id.widget_bar, 100, 0, false);
            v.setTextViewText(R.id.widget_spent, "Mở FinBud để nhập ngân sách tháng");
            v.setTextViewText(R.id.widget_tomorrow, "");
            return v;
        }

        int bg = l.ratio > 1 ? R.drawable.widget_bg_bad : l.ratio >= 0.8 ? R.drawable.widget_bg_warn : R.drawable.widget_bg_ok;
        v.setInt(R.id.widget_card, "setBackgroundResource", bg);
        v.setTextViewText(R.id.widget_amount, l.rem >= 0 ? fmt(l.rem) : "Vượt " + fmt(-l.rem));
        v.setProgressBar(R.id.widget_bar, 100, (int) Math.round(Math.min(100, l.ratio * 100)), false);
        v.setTextViewText(R.id.widget_spent, "Đã chi " + fmt(l.tdy) + " / " + fmt(l.limit) + (l.ratio > 1 ? " · Vượt hạn mức" : ""));
        v.setTextViewText(R.id.widget_tomorrow, "Ngày mai dự kiến " + fmt(l.tomorrow));
        return v;
    }

    // ---------------- Dữ liệu dùng chung với app ----------------

    static JSONObject readData(Context ctx) {
        try {
            String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DATA, null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static synchronized JSONArray readPending(Context ctx) {
        try {
            return new JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Thêm một giao dịch {id, a, c, n, s, d} vào hàng chờ. */
    static synchronized void addPending(Context ctx, JSONObject tx) {
        JSONArray a = readPending(ctx);
        a.put(tx);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING, a.toString()).commit();
    }

    /** Lấy toàn bộ hàng chờ và xoá đi (app gọi khi mở để đưa vào Lịch sử). */
    static synchronized JSONArray takePending(Context ctx) {
        JSONArray a = readPending(ctx);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING).commit();
        return a;
    }

    /** Cộng các giao dịch còn trong hàng chờ vào tổng chi theo ngày, để widget đúng ngay cả khi app chưa mở. */
    static JSONObject withPending(JSONObject d, JSONArray pending) throws Exception {
        if (pending.length() == 0) return d;
        JSONObject c = new JSONObject(d.toString());
        JSONObject daily = c.optJSONObject("daily");
        if (daily == null) c.put("daily", daily = new JSONObject());
        for (int i = 0; i < pending.length(); i++) {
            JSONObject t = pending.getJSONObject(i);
            String day = t.optString("d");
            daily.put(day, daily.optDouble(day, 0) + t.optDouble("a", 0));
        }
        return c;
    }

    /** Kết quả tính hạn mức, cùng ý nghĩa với các trường trong calc() của index.html. */
    static final class Limit {
        double tdy, limit, rem, ratio, tomorrow;
    }

    /**
     * Hạn mức ngày = (Có thể chi − Đã chi trước hôm nay) ÷ số ngày còn lại (gồm hôm nay)
     * Có thể chi    = Ngân sách − Chi phí cố định + Tiền thêm trong tháng
     * data: { ready, budget, fixed, extra: {"yyyy-MM": số}, daily: {"yyyy-MM-dd": số} }
     */
    static Limit compute(JSONObject d, Calendar now) {
        if (!d.optBoolean("ready")) return null;
        int day = now.get(Calendar.DAY_OF_MONTH), dim = now.getActualMaximum(Calendar.DAY_OF_MONTH), left = dim - day + 1;
        String ym = String.format(Locale.US, "%04d-%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1);
        String td = ym + String.format(Locale.US, "-%02d", day);

        JSONObject extra = d.optJSONObject("extra"), daily = d.optJSONObject("daily");
        double pool = d.optDouble("budget", 0) - d.optDouble("fixed", 0) + (extra != null ? extra.optDouble(ym, 0) : 0);
        double spent = 0, tdy = 0;
        if (daily != null) {
            for (Iterator<String> it = daily.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!k.startsWith(ym)) continue;
                double a = daily.optDouble(k, 0);
                spent += a;
                if (k.equals(td)) tdy += a;
            }
        }
        Limit l = new Limit();
        l.tdy = tdy;
        l.limit = Math.max((pool - (spent - tdy)) / left, 0);
        l.rem = l.limit - tdy;
        l.ratio = l.limit > 0 ? tdy / l.limit : (tdy > 0 ? 2 : 1);
        l.tomorrow = left > 1 ? Math.max((pool - spent) / (left - 1), 0) : 0;
        return l;
    }

    /** 115789.4 -> "115.789đ" (giống fmt() trong app). */
    static String fmt(double n) {
        NumberFormat nf = NumberFormat.getIntegerInstance(new Locale("vi", "VN"));
        nf.setGroupingUsed(true);
        return nf.format(Math.round(n)).replace(',', '.').replace(' ', '.') + "đ";
    }

    /** Nút "Nói" (listen = true: nghe ngay) và "Gõ": mở pop-up nhập nhanh ở phần dưới màn hình, không mở app. */
    private static PendingIntent quickAdd(Context ctx, boolean listen) {
        Intent i = new Intent(ctx, QuickAddActivity.class);
        i.setAction("com.tnn.finbud.QUICK_" + (listen ? "VOICE" : "TYPE"));   // khác action để 2 nút không dùng chung PendingIntent
        i.putExtra(QuickAddActivity.EXTRA_LISTEN, listen);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return PendingIntent.getActivity(ctx, listen ? 1 : 3, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Mở app; action "quick"/"ocr" được WidgetPlugin chuyển cho JS để mở đúng bảng. */
    private static PendingIntent open(Context ctx, String action, int requestCode) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setAction("com.tnn.finbud.WIDGET_" + action);      // action khác nhau để Android không gộp các PendingIntent
        i.putExtra(EXTRA_ACTION, action);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
