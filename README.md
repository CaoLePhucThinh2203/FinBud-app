# FinBud – Trợ lý quản lý chi tiêu cho sinh viên

Ứng dụng Android (Capacitor) theo PRD *FinBud v1.0* – nhóm TNN Innovators.

## Tính năng trong bản này
- **Daily Spending Widget**: "Hôm nay bạn có thể chi tiêu: X đ", đổi màu xanh/vàng/đỏ (<80% / 80–100% / >100%), tự tính lại sau mỗi giao dịch.
- **Hạn mức ngày** = (Ngân sách − Chi phí cố định − Đã chi trước hôm nay) ÷ số ngày còn lại; thâm hụt/dư tự dồn vào các ngày sau (Rolling Budget).
- **Nhập nhanh** bằng giọng nói hoặc gõ: hiểu `15k`, `hai chục`, `5 xị`, `nửa triệu`, `ba lăm nghìn`, `45.000 VND`; tự phân loại danh mục; sửa được trước khi lưu, tự lưu sau 3 giây nếu không chạm vào.
- **Quét hóa đơn**: chụp bằng camera, cảnh báo ảnh mờ, OCR tìm dòng "Tổng tiền" (Tesseract.js, tải từ CDN khi dùng lần đầu – cần mạng). Nếu OCR thất bại, nhập tay tổng tiền.
- Lưu dữ liệu trên máy (localStorage), xem/xóa lịch sử, chỉnh ngân sách trong Cài đặt.

## Chưa có (cần code native Android, làm ở bước sau)
- Tự động đọc thông báo ngân hàng (`NotificationListenerService`, whitelist MB/Vietcombank/MoMo…).
- Widget trên màn hình chính của Android (`AppWidgetProvider`).
- Nhận giọng nói trong WebView: Android WebView không có Web Speech API; hiện dùng micro của bàn phím (Google Voice Typing). Muốn bấm-giữ-nói cần plugin native.
- Mã hóa AES-256 cơ sở dữ liệu, đồng bộ offline lên máy chủ.

## Build APK
**Cách 1 – GitHub Actions (không cần cài gì):** đẩy code lên nhánh `main` → tab *Actions* → *Build Android APK* → tải `FinBud-APK` ở mục *Artifacts*.

**Cách 2 – Trên máy:** cần Node ≥ 22, JDK 21, Android SDK (platform 36).
```bash
npm install
npx cap sync android
cd android && ./gradlew assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```
Sau mỗi lần sửa `www/`, chạy lại `npx cap sync android`.

Chạy thử giao diện nhanh trên trình duyệt: mở `www/index.html`.
