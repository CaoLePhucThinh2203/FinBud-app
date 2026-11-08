# FinBud – Trợ lý Quản lý Chi tiêu Thông minh cho Sinh viên

> **FinBud** là ứng dụng di động (Android / Capacitor) giúp sinh viên gạt bỏ rào cản ghi chép thủ công tốn thời gian, giải quyết bài toán mất kiểm soát tài chính tức thời thông qua con số định mức chi tiêu an toàn mỗi ngày: **"Hôm nay mình có thể chi tiêu bao nhiêu tiền?"**

Sản phẩm được nghiên cứu và phát triển theo quy trình Design Thinking bởi nhóm **TNN Innovators** khóa 24SE (Viện Nghiên cứu và Đào tạo Việt - Anh, Đại học Đà Nẵng).

---

## Vấn đề & Giải pháp cốt lõi

* **Vấn đề thực tế:** 61,5% sinh viên gặp khó khăn vì có quá nhiều giao dịch nhỏ lẻ và 42,3% dễ quên ghi chép. Các ứng dụng ngân hàng hiện tại chỉ hiển thị số dư và lịch sử giao dịch trong quá khứ, không hỗ trợ đưa ra quyết định chi tiêu ngay tại thời điểm mua sắm.
* **Cách FinBud giải quyết:**
  * **Zero-effort Input:** Nhập liệu đa phương thức bằng AI chạy trên máy (giọng nói tiếng Việt offline, quét hóa đơn).
  * **Daily Safe-to-Spend:** Chuyển dữ liệu tài chính phức tạp thành một con số duy nhất: hạn mức chi tiêu khả dụng trong ngày.

---

## Tính năng hiện có

### 1. Daily Spending Widget & Thuật toán Rolling Budget
* **Con số thời gian thực:** Hiển thị số tiền bạn được phép chi trong ngày hôm nay.
* **Cảnh báo 3 cấp độ màu:**
  * **Xanh:** An toàn (< 80% hạn mức ngày).
  * **Vàng:** Sắp chạm trần (80% – 100% hạn mức).
  * **Đỏ:** Vượt hạn mức ngày (kèm cảnh báo lạm chi).
* **Cân bằng động (Dynamic Rolling Budget):**

  $$\text{Hạn mức ngày} = \frac{\text{Ngân sách tháng} - \text{Chi phí cố định} - \text{Đã tiêu trước hôm nay}}{\text{Số ngày còn lại trong tháng}}$$

  * Chi **vượt mức** hôm nay → phần thâm hụt tự động chia đều vào các ngày còn lại.
  * Chi **tiết kiệm** → khoản dư dồn sang các ngày sau.
* Màn hình trang chủ còn hiện hạn mức dự kiến của ngày mai.

### 2. Nhập nhanh bằng giọng nói tiếng Việt (Offline – Vosk)
* **Chạy hoàn toàn trên máy:** Model Vosk tiếng Việt được đóng gói trong APK, nhận diện bằng CPU điện thoại, không cần mạng.
* **Hiểu cách nói của sinh viên:**
  * *"ăn bún bò 35k"* → 35.000đ
  * *"gửi xe hai chục"* → 20.000đ
  * *"mua áo 5 xị"* → 500.000đ
  * *"đi grab nửa triệu"* → 500.000đ
  * *"photo tài liệu ba lăm nghìn"* → 35.000đ
  * Cũng đọc được số dạng `45.000 VND`, `2 triệu rưỡi`, và dán được nội dung thông báo ngân hàng vào ô nhập.
* **Tự phân loại danh mục:** Ăn uống, Di chuyển, Học tập, Mua sắm, Khác.
* **Xác nhận nhanh:** tự lưu sau 3 giây; chạm vào ô nào thì dừng đếm ngược để sửa số tiền, danh mục, ghi chú.
* **Dự phòng:** nếu máy không chạy được Vosk, app dùng Web Speech API (nếu có) hoặc bạn bấm micro trên bàn phím rồi bấm *Phân tích*.

### 3. Quét hóa đơn bán lẻ (OCR chạy trên máy)
Chụp hóa đơn → app tự điền **tổng tiền, ngày, người bán và danh mục**, bạn kiểm tra rồi lưu.

```mermaid
flowchart LR
  A[Chụp hóa đơn] --> B[Kiểm tra độ nét]
  B --> C[ML Kit OCR trên máy]
  C --> D[receipt-engine.js<br/>gộp dòng · chấm điểm · kiểm tra chéo]
  D --> E[Tổng tiền · Ngày · Người bán · Danh mục]
  E --> F[Người dùng kiểm tra và lưu]
```

* **OCR offline:** Plugin `ReceiptScanner` dùng Google ML Kit (bộ chữ Latin, gồm tiếng Việt), model đóng gói sẵn trong APK nên quét được khi không có mạng.
* **Trích xuất thông minh (`receipt-engine.js`):** gộp các ô chữ thành từng hàng, chấm điểm hàng "Tổng cộng / Thanh toán", loại trừ hàng "Tiền mặt / Tiền thừa / Giảm giá", và kiểm tra chéo *tiền khách đưa − tiền thừa = tổng*. Số bị OCR đọc nhầm (ví dụ `1O5,OOO`) được sửa lại.
* **Phân loại chi tiêu:** dựa trên tên cửa hàng và nội dung hóa đơn (từ điển thương hiệu + mô hình học máy nhẹ nếu có `receipt_model.json`).
* **Khi chưa chắc chắn:** app hiện 2–3 số tiền ứng viên để chạm chọn và nhắc kiểm tra kỹ.
* **Ngày trên hóa đơn:** được dùng làm ngày giao dịch nếu không quá 45 ngày, nếu cũ hơn thì ghi theo hôm nay.
* **Kiểm tra độ nét ảnh** (Laplacian) cảnh báo khi ảnh mờ.
* **Dự phòng:** nếu ML Kit không dùng được thì thử Tesseract.js (cần mạng lần đầu); không đọc được thì mở sẵn ô nhập tay.
* Nút **📋 Sao chép JSON OCR** giúp thu thập mẫu thật để đánh giá và cải thiện mô hình.

### 4. Lịch sử & Ngân sách cá nhân
* Thống kê chi tiêu tháng này theo từng danh mục; xem và xóa giao dịch trong lịch sử.
* Chỉnh tên, ngân sách khả dụng, chi phí cố định trong Cài đặt.
* Dữ liệu lưu cục bộ trên thiết bị (Local Storage).

---

## Mô hình quét hóa đơn (notebook)

Notebook `QuetHoaDon_hoan_thien.ipynb` huấn luyện phần "hiểu hóa đơn" rồi xuất file cho app:

| Giai đoạn | Nội dung |
|---|---|
| Stage 0–2 | Tiền xử lý ảnh → CRAFT phát hiện chữ → VietOCR đọc chữ (tạo dữ liệu từ bộ ảnh MC-OCR 2021) |
| Stage 3 *(tuỳ chọn)* | Trích đặc trưng LayoutXLM. **Tắt mặc định** (`USE_LAYOUTXLM = False`) vì cần detectron2, không cài được trên Windows |
| Stage 4 | Gộp ô thành hàng, gán nhãn `SELLER / ADDRESS / TIMESTAMP / TOTAL_COST / OTHER` từ annotation, huấn luyện bộ phân loại hàng |
| Đánh giá | Chỉ số chính: **tỷ lệ đúng chính xác tổng tiền**, so sánh chế độ "luật" và "luật + mô hình" |
| Stage 5 | Phân loại chi tiêu: `food / move / study / shop / other` |
| Stage 6 | Xuất `receipt_model.json` và `receipt_lexicon.json` |
| Stage 7 *(tuỳ chọn)* | Đánh giá lại trên OCR thật của ML Kit để phát hiện lệch giữa lúc train và lúc chạy |

**Cập nhật mô hình cho app:** chạy notebook → chép `receipt_model.json`, `receipt_lexicon.json` vào `www/` → `npx cap sync android` → build lại. Không có `receipt_model.json` thì app vẫn chạy bằng luật.

> Logic đặc trưng trong cell "Lõi dùng chung" của notebook và `www/receipt-engine.js` phải **luôn giống nhau**. Sửa một bên thì sửa bên còn lại.

---

## Cấu trúc dự án

```
FinBud-app/
├─ www/
│  ├─ index.html               # Giao diện + logic ứng dụng
│  ├─ receipt-engine.js        # Trích tổng tiền / ngày / người bán / danh mục
│  ├─ receipt_lexicon.json     # Từ khóa + danh mục (sinh từ notebook)
│  └─ receipt_model.json       # Mô hình đã huấn luyện (tuỳ chọn)
├─ android/app/src/main/
│  ├─ assets/model-vn/         # Model Vosk tiếng Việt (+ file uuid)
│  └─ java/com/tnn/finbud/
│     ├─ MainActivity.java     # Đăng ký plugin
│     ├─ VoskPlugin.java       # Giọng nói offline
│     └─ ReceiptScannerPlugin.java  # OCR ML Kit
├─ capacitor.config.json
└─ .github/workflows/build-apk.yml
```

---

## Cài đặt & Đóng gói (Build APK)

### Cách 1 – GitHub Actions (không cần cài môi trường)
1. Đẩy mã nguồn lên nhánh `main` (hoặc `master`).
2. Mở tab **Actions** → chọn workflow **Build Android APK** → **Run workflow** (hoặc chờ tự chạy khi push).
3. Khi tiến trình báo thành công ✔, tải file zip `FinBud-APK` ở mục **Artifacts** và cài `app-debug.apk`.

### Cách 2 – Build trên máy
Yêu cầu: **Node.js ≥ 22**, **JDK 21**, Android SDK (platform 36).
```bash
npm install
npx cap sync android
cd android && ./gradlew assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```
Sau mỗi lần sửa thư mục `www/`, chạy lại `npx cap sync android`.

Chạy thử giao diện nhanh trên trình duyệt: mở `www/index.html` (OCR ML Kit và Vosk chỉ hoạt động trong app Android).

> APK khá nặng vì đóng gói sẵn model giọng nói Vosk và model ML Kit (thêm khoảng 38 MB) để dùng được khi không có mạng.

---

## Xử lý sự cố giọng nói

| Triệu chứng | Nguyên nhân thường gặp |
|---|---|
| "Model chưa sẵn sàng" | Thư mục model phải tên đúng `assets` (không phải `assests`), nằm ở `android/app/src/main/assets/model-vn/` |
| Lỗi nạp model, báo thiếu `uuid` | Cần file văn bản `uuid` (một dòng bất kỳ) trong `assets/model-vn/`; đổi nội dung khi thay model |
| Không bật được micro | Chưa cấp quyền micro; app xin quyền ở lần bấm 🎤 đầu tiên |
| Không thấy lỗi gì | Mở `chrome://inspect` xem console WebView, hoặc `adb logcat` lọc `Capacitor`, `vosk` |

---

## Lộ trình phát triển

- [x] Daily Spending Widget và Rolling Budget trong app
- [x] Nhập liệu giọng nói tiếng Việt offline (Vosk)
- [x] Quét hóa đơn offline bằng ML Kit, tự điền tổng tiền, ngày, người bán, danh mục
- [x] Notebook huấn luyện bộ trích xuất hóa đơn trên dữ liệu MC-OCR
- [ ] **Đánh giá trên hóa đơn thật của sinh viên:** thu thập 100–200 mẫu (trà sữa, tạp hóa, siêu thị), đo độ chính xác tổng tiền và cải thiện mô hình
- [ ] **Tự động đọc thông báo biến động số dư (Coming soon):** lắng nghe thông báo từ Vietcombank, MB Bank, Techcombank, MoMo, ZaloPay... qua dịch vụ Android Native với whitelist bảo vệ quyền riêng tư
- [ ] **Home Screen Widget (Android AppWidget):** đưa con số chi tiêu ngày ra màn hình chính/màn hình khóa
- [ ] **Bóc tách từng dòng sản phẩm:** mở rộng pipeline CRAFT + VietOCR / mô hình hiểu bố cục để lấy chi tiết món hàng
- [ ] **Bảo mật & đồng bộ:** mã hóa cơ sở dữ liệu AES-256, sao lưu và đồng bộ đa thiết bị

---

## Giới hạn hiện tại

* Chất lượng ML Kit với hóa đơn tiếng Việt chưa được đánh giá trên tập ảnh thật quy mô lớn; hãy luôn kiểm tra số tiền trước khi lưu.
* Số tiền viết kiểu có dấu cách ngăn nghìn (ví dụ `100 000`) chưa được hỗ trợ.
* Bộ ảnh MC-OCR thiên về siêu thị và cửa hàng tiện lợi; hóa đơn quán nhỏ có thể cho kết quả kém hơn.
* Hóa đơn quá dài hoặc chụp nghiêng, nhàu có thể làm giảm độ chính xác.
* Chưa có đọc thông báo ngân hàng tự động và widget màn hình chính.
