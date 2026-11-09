package com.tnn.finbud;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Lắng nghe thông báo biến động số dư. Nguyên tắc riêng tư:
 *  1) Gói ứng dụng không nằm trong danh sách ngân hàng/ví đã khai báo -> bỏ qua NGAY, không đọc nội dung.
 *  2) Ngân hàng người dùng chưa bật -> bỏ qua.
 *  3) Thông báo có OTP / mật khẩu -> bỏ, không lưu.
 *  4) Chỉ lưu giao dịch chi tiêu đã phân tích (số tiền, nội dung ngắn, thời gian). Không lưu văn bản gốc.
 */
public class BankNotificationService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (Exception ignored) {
            // Không để lỗi phân tích làm sập dịch vụ
        }
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        String bank = BankStore.labelOf(pkg);
        if (bank == null) return;
        if (!BankStore.isEnabled(this, pkg)) return;

        Notification n = sbn.getNotification();
        if (n == null) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle ex = n.extras;
        if (ex == null) return;

        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = ex.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        String body = (big != null && big.length() > 0) ? big.toString() : (text != null ? text.toString() : "");
        String all = ((title != null ? title.toString() : "") + " " + body).trim();
        if (all.isEmpty()) return;

        if (BankParser.isSensitive(all)) return;

        BankParser.Result r = BankParser.parse(all);
        if (r == null) {
            BankStore.addSample(this, bank, BankParser.mask(all));
            return;
        }
        if (!r.outgoing) return;   // FinBud chỉ theo dõi chi tiêu
        String note = r.note.isEmpty() ? bank : r.note;
        BankStore.addTx(this, pkg, r.amount, note, sbn.getPostTime(), BankParser.signature(all));
    }
}
