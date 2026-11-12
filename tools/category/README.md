# Train bộ phân loại danh mục chi tiêu

Mô hình: TF-IDF n-gram ký tự (2–4) + hồi quy logistic, 7 danh mục:
`food, move, study, shop, health, fun, other`.
Tiền nhà, điện, nước, mạng nằm trong "Chi phí cố định" nên không có danh mục riêng; nếu gõ vào thì được xếp vào `other`.
Kết quả xuất vào `www/receipt_model.json` (mục `cat`) và `www/receipt_lexicon.json` (mục `cat_kw`),
được `www/receipt-engine.js` dùng cho Nói/gõ, Quét hóa đơn và Giả lập thông báo.

## Các file

| File | Vai trò |
|---|---|
| `seed.py` | Từ vựng gốc theo danh mục: món, thương hiệu, động từ. **Thêm từ mới ở đây.** |
| `gen_data.py` | Sinh ~12.000 câu train (ghi chú, nội dung chuyển khoản, đoạn hóa đơn) từ seed → `data/train.csv` |
| `test_set.csv` | Bộ test viết tay, **không** sinh từ seed. Chỉ dùng để đo, không dùng để train. |
| `train.py` | Train, đánh giá, ghi model + từ điển cho app |
| `check_js.js` | Chạy đúng `receipt-engine.js` trên bộ test để chắc JS cho cùng kết quả với Python |

## Chạy

```bash
pip install scikit-learn numpy
python tools/category/gen_data.py
python tools/category/train.py --dry-run   # xem kết quả trước, không ghi file
python tools/category/train.py
node tools/category/check_js.js
```

## Lưu ý

- Bước tiền xử lý trong `train.py` (`fold`, `norm_tok`) phải giống hệt `fold`/`normTok` trong `receipt-engine.js`.
- Khi app nhận sai một từ: thêm từ đó vào `seed.py` (và nên thêm một câu tương tự vào `test_set.csv`), rồi chạy lại.
- Con số "câu có từ chưa gặp trong seed" là thước đo đáng tin hơn độ chính xác chung,
  vì phần lớn câu test có chứa từ đã nằm trong seed.
- So sánh với model cũ (5 lớp): `train.py` đọc `data/cat_v1.json` nếu có. Tạo lại bằng
  `git show main:www/receipt_model.json` rồi lấy mục `cat`.
