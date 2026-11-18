# Luật đọc hóa đơn từ QuetHoaDon

`www/receipt-rules.js` là bản JS của các luật thuần Python trong đồ án
[QuetHoaDon](https://github.com/CaoLePhucThinh2203/QuetHoaDon) (`src/extraction/`):

| Python (QuetHoaDon) | JS (FinBud) |
|---|---|
| `line_items.py`: `group_lines`, `parse_amounts`, `extract_items`, `check_items` | `groupLines`, `parseAmounts`, `extractItems`, `checkItems` |
| `datetime_rules.py`: `extract_time`, `datetime_candidates`, `read_datetime`; `field_eval.normalize_date` | `extractTime`, `datetimeCandidates`, `readDatetime`, `normalizeDate` |
| `rules.py`: `fold`, `rule_total`; `decode.py`: `merge_split_amount`, `parse_amount_token`, `amount_candidates`; `metrics.normalize_amount` | `fold`, `ruleTotal`, `mergeSplitAmount`, `parseAmountToken`, `amountCandidates`, `normalizeAmount` |
| `field_cleanup.py`: `dedupe_tokens`, `strip_trailing_junk`, `clean_field` | `dedupeTokens`, `stripTrailingJunk`, `cleanField` |

`receipt-engine.js` gọi các luật này sau bước chấm điểm dòng (`applyRules`): danh sách món, xác nhận tổng khi tổng các
món bằng một số in trên hóa đơn, ngày giờ, làm sạch tên cửa hàng.

## Chọn tên cửa hàng (riêng của FinBud, không có trong QuetHoaDon)

`receipt-engine.js` (`pickSeller`) bỏ qua dòng tiêu đề / nhãn khi chọn tên cửa hàng. Kiểm tra trên 12 kiểu đầu hóa đơn và
21 tên / nhãn dễ nhầm:

```bash
node tools/receipt-rules/check_seller.js
```

## Kiểm tra JS khớp Python

Cần một bản clone QuetHoaDon (chỉ đọc, script không ghi gì vào đó) và Python có `numpy`:

```bash
git clone --depth 1 https://github.com/CaoLePhucThinh2203/QuetHoaDon.git ../QuetHoaDon
python tools/receipt-rules/check_rules.py ../QuetHoaDon
```

Script sinh ngẫu nhiên (seed cố định) khoảng 22.500 ca — hóa đơn có dòng hàng, giảm giá, mã vạch, nhiều kiểu viết số;
ngày giờ bị OCR tách; số tiền bị tách; tên cửa hàng dính số điện thoại — chạy cùng ca qua hàm Python gốc và hàm JS rồi
so từng kết quả. Khi QuetHoaDon sửa luật, sửa `receipt-rules.js` theo rồi chạy lại.
