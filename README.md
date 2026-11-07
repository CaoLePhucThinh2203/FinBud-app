# 💸 FinBud – Trợ lý Quản lý Chi tiêu Thông minh cho Sinh viên

> **FinBud** là ứng dụng di động (Android / Capacitor) giúp sinh viên gạt bỏ rào cản ghi chép thủ công tốn thời gian, giải quyết bài toán mất kiểm soát tài chính tức thời thông qua con số định mức chi tiêu an toàn mỗi ngày: **"Hôm nay mình có thể chi tiêu bao nhiêu tiền?"**

Sản phẩm được nghiên cứu và phát triển theo quy trình Design Thinking bởi nhóm **TNN Innovators** khóa 24SE (Viện Nghiên cứu và Đào tạo Việt - Anh, Đại học Đà Nẵng).

---

## 🎯 Vấn đề & Giải pháp cốt lõi

* **Vấn đề thực tế:** 61,5% sinh viên gặp khó khăn vì có quá nhiều giao dịch nhỏ lẻ và 42,3% dễ quên ghi chép. Các ứng dụng ngân hàng hiện tại chỉ hiển thị số dư và lịch sử giao dịch trong quá khứ, không hỗ trợ đưa ra quyết định chi tiêu ngay tại thời điểm mua sắm.
* **Cách FinBud giải quyết:**
  * **Zero-effort Input:** Tối ưu hóa việc nhập liệu đa phương thức bằng AI (giọng nói offline tiếng Việt, quét hóa đơn).
  * **Daily Safe-to-Spend:** Chuyển đổi dữ liệu tài chính phức tạp thành một con số duy nhất: hạn mức chi tiêu khả dụng trong ngày.

---

## ⚡ Các tính năng nổi bật (Current Features)

### 1. Daily Spending Widget & Thuật toán Rolling Budget
* **Con số thời gian thực:** Hiển thị trực quan số tiền khả dụng bạn được phép chi tiêu trong ngày hôm nay.
* **Cảnh báo trạng thái 3 cấp độ màu:**
  * 🟢 **Xanh:** Chi tiêu an toàn (< 80% hạn mức ngày).
  * 🟡 **Vàng:** Cảnh báo sắp chạm trần (80% – 100% hạn mức).
  * 🔴 **Đỏ:** Vượt hạn mức ngày (Kèm cảnh báo lạm chi).
* **Cơ chế cân bằng động (Dynamic Rolling Budget):** 
  $$\text{Hạn mức ngày} = \frac{\text{Ngân sách tháng} - \text{Chi phí cố định} - \text{Đã tiêu trước hôm nay}}{\text{Số ngày còn lại trong tháng}}$$
  * Nếu hôm nay chi tiêu **vượt mức**, phần thâm hụt sẽ tự động chia đều để khấu trừ vào các ngày còn lại trong tháng.
  * Nếu chi tiêu **tiết kiệm**, khoản dư sẽ được dồn sang các ngày tiếp theo.

### 2. Nhập nhanh bằng giọng nói tiếng Việt Offline (Vosk AI)
* **Xử lý On-Device (Offline 100%):** Tích hợp mô hình nhận diện giọng nói tiếng Việt Vosk trực tiếp trong ứng dụng, suy luận ngay trên CPU điện thoại, không cần kết nối mạng và đảm bảo bảo mật dữ liệu.
* **Hiểu ngôn ngữ tự nhiên & tiếng lóng tiền tệ:** Tự động quy đổi các cách nói quen thuộc của sinh viên:
  * *"ăn bún bò 35k"* $\rightarrow$ 35.000đ
  * *"gửi xe hai chục"* $\rightarrow$ 20.000đ
  * *"mua áo 5 xị"* $\rightarrow$ 500.000đ
  * *"đi grab nửa triệu"* $\rightarrow$ 500.000đ
  * *"photo tài liệu ba lăm nghìn"* $\rightarrow$ 35.000đ
* **Tự động phân loại danh mục:** Gán nhãn thông minh vào các nhóm: *Ăn uống, Di chuyển, Học tập, Mua sắm, Khác*.
* **Xác nhận 1 chạm:** Tự động đếm ngược lưu sau 3 giây hoặc cho phép chỉnh sửa nhanh trước khi lưu.

### 3. Quét hóa đơn bán lẻ (OCR Scanner)
* **Mở máy ảnh chụp trực tiếp:** Tích hợp bộ lọc kiểm tra độ sắc nét ảnh (Laplacian Sharpness Check) cảnh báo khi ảnh chụp bị mờ/nhòe.
* **Tự động bóc tách tổng tiền:** Quét và trích xuất trường *Tổng cộng / Total / Thanh toán* trên hóa đơn siêu thị, cửa hàng tiện lợi.
* **Fallback linh hoạt:** Tự động mở sẵn ô nhập tay nếu hóa đơn quá mờ hoặc mất kết nối CDN lần đầu.

### 4. Quản lý lịch sử & Ngân sách cá nhân
* Thống kê trực quan cơ cấu chi tiêu theo từng danh mục trong tháng.
* Đo lường tỷ lệ các giao dịch được ghi nhận tự động bằng AI.
* Lưu trữ dữ liệu an toàn trên bộ nhớ thiết bị (Local Storage).

---

## 🚀 Lộ trình phát triển sắp tới (Upcoming Features)

- [ ] **Tự động đọc thông báo biến động số dư ngân hàng (Coming soon):** Lắng nghe thông báo ngầm từ các ứng dụng ngân hàng và ví điện tử phổ biến tại Việt Nam (Vietcombank, MB Bank, Techcombank, MoMo, ZaloPay...) qua dịch vụ Android Native Service với whitelist bảo vệ quyền riêng tư.
- [ ] **Home Screen Widget (Android AppWidget):** Tiện ích đưa con số chi tiêu ngày ra màn hình khóa và màn hình chính của điện thoại mà không cần mở ứng dụng.
- [ ] **Nâng cấp Pipeline OCR & KIE chuyên sâu:** Tích hợp mô hình học sâu (CRAFT Text Detection + VietOCR / LayoutXLM) để bóc tách chi tiết từng dòng sản phẩm trên hóa đơn bán lẻ phức tạp.
- [ ] **Đồng bộ hóa đám mây & Bảo mật nâng cao:** Mã hóa cơ sở dữ liệu cấp thiết bị theo tiêu chuẩn AES-256, hỗ trợ sao lưu và đồng bộ tài khoản đa thiết bị.

---

## 🛠️ Hướng dẫn cài đặt & Đóng gói (Build APK)

### Tải APK tự động qua GitHub Actions (Không cần cài môi trường máy)
1. Đẩy mã nguồn lên nhánh `main` (hoặc `master`) trên GitHub Repository.
2. Chuyển sang tab **Actions** $\rightarrow$ Chọn workflow **Build Android APK**.
3. Bấm **Run workflow** (hoặc đợi tiến trình tự kích hoạt).
4. Sau khi tiến trình báo thành công ($\checkmark$), cuộn xuống mục **Artifacts** tải file zip `FinBud-APK` về máy và cài đặt file `app-debug.apk`.