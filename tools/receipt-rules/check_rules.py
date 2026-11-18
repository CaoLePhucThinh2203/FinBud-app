"""Kiểm tra www/receipt-rules.js cho kết quả GIỐNG HỆT các luật Python gốc của đồ án QuetHoaDon.

Sinh ngẫu nhiên (cố định theo seed) hàng nghìn ca: hóa đơn có dòng hàng, giảm giá, mã vạch, tổng tiền; ngày giờ bị
OCR tách; số tiền bị tách ("107,0" + ",000"); tên cửa hàng dính số điện thoại... rồi chạy cùng một ca qua hàm Python
gốc và hàm JS, so từng kết quả.

Chỉ ĐỌC mã nguồn QuetHoaDon (không ghi gì vào thư mục đó, kể cả __pycache__).
Chạy:  python tools/receipt-rules/check_rules.py <đường dẫn tới bản clone QuetHoaDon>
"""
import json, os, random, subprocess, sys

sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
if len(sys.argv) < 2:
    sys.exit("Cần đường dẫn tới bản clone QuetHoaDon")
sys.path.insert(0, os.path.abspath(sys.argv[1]))
from src.extraction import datetime_rules as DT, field_cleanup as FC, line_items as LI, rules as RU, decode as DE  # noqa: E402
from src.training.field_eval import normalize_date  # noqa: E402
from src.evaluation.metrics import normalize_amount  # noqa: E402

R = random.Random(2203)
ch = R.choice

NAMES = ["Sữa tươi Vinamilk", "MI HAO HAO", "Bánh Oreo", "Coca Cola 330ml", "Trà xanh 0 độ", "Nước suối Aquafina", "Kẹo bạc hà",
         "TRỨNG GÀ", "Bánh mì sandwich", "Dầu ăn Neptune 1L", "Giấy vệ sinh", "Cà phê sữa đá", "Trà sữa trân châu", "Snack Oishi",
         "Xúc xích", "Sting dâu", "Mì Omachi", "Rau muống", "Thịt heo xay", "Bột giặt OMO"]
UNITS = ["", "", "", "goi", "hop", "chai", "cai", "lon", "kg", "x", "Hộp", "GÓI"]
HEADERS = ["Tên hàng SL Đơn giá Thành tiền", "TEN HANG SL DON GIA T.TIEN", "Mặt hàng Số lượng Giá Thành tiền", "Tên món SL Thành tiền",
           "Hàng hóa ĐVT SL Giá TT", "STT Tên hàng SL Đ.giá T.tiền"]
TOPS = ["HÓA ĐƠN BÁN LẺ", "Ngày: 20/09/2019 14:35", "Thu ngân: Lan", "Số HĐ: 0012345", "Quầy 02", "PHIẾU THANH TOÁN", "NVBH: Minh"]
ENDS = ["Tổng cộng:", "TONG CONG", "Thanh toán", "Tổng tiền hàng", "Phải trả", "TỔNG THANH TOÁN", "Cộng tiền hàng"]
AFTER = ["Tiền khách đưa", "Tiền thừa", "Tiền mặt", "VAT 10%", "Chiết khấu", "Cảm ơn quý khách"]
SELLERS = ["CO.OP FOOD", "Siêu thị Vinmart", "BÁCH HÓA XANH", "Circle K", "Highlands Coffee", "Nhà thuốc Long Châu", "Cửa hàng tiện lợi GS25"]


def money(v):
    s = f"{v:,}"
    return ch([s, s.replace(",", "."), s + "đ", s.replace(",", " ."), s.replace(",", ". "), s.replace(",", " "), s + ".00",
               s + " VND", f"{v}", s.replace(",", ", ")])


def item_lines():
    lines, total = [], 0
    for _ in range(R.randint(0, 6)):
        q, p = ch([1, 1, 1, 2, 3, 4, 5, 10, 12]), ch([5, 6.5, 8, 10, 12.5, 15, 24, 36.5, 45, 110, 250]) * 1000
        p = int(p)
        amt = q * p
        total += amt
        name = ch(NAMES)
        code = ch(["", "", f"{R.randint(10**7, 10**12)}", f"{R.randint(1, 30)}."])
        unit = ch(UNITS)
        layout = R.random()
        if layout < 0.4:
            lines.append(" ".join(x for x in [code, name, str(q), unit, money(p), money(amt)] if x))
        elif layout < 0.75:
            lines.append(" ".join(x for x in [code, name] if x))
            lines.append(" ".join(x for x in [str(q), unit, money(p), money(amt)] if x))
        else:
            lines.append(" ".join(x for x in [name, money(amt)] if x))
        if R.random() < 0.15:
            d = ch([1000, 2000, 3300, 5000])
            total -= d
            lines.append(ch([f"KM giảm giá -{money(d)}", f"Khuyến mãi {money(d)}", f"CK -{money(d)}", f"Giảm -{d}"]))
        if R.random() < 0.08:
            lines.append(ch(["Giá niêm yết 50,000", "Giá gốc 120.000", "Giá bán: 12 HOP 6,500 78,000"]))
    return lines, total


def receipt():
    lines = [ch(SELLERS)] + R.sample(TOPS, R.randint(0, 3))
    if R.random() < 0.7:
        lines.append(ch(HEADERS))
    items, total = item_lines()
    lines += items
    if R.random() < 0.85:
        lines.append(f"{ch(ENDS)} {money(total if R.random() < 0.8 else total + ch([1000, 500, 10000]))}")
    lines += R.sample(AFTER, R.randint(0, 3))
    words, boxes, y = [], [], R.uniform(10, 40)
    for ln in lines:
        h = R.uniform(14, 30)
        x = R.uniform(5, 40)
        for t in ln.split():
            w = len(t) * R.uniform(7, 11)
            jy = R.uniform(-0.15, 0.15) * h
            words.append(t)
            boxes.append([round(x, 2), round(y + jy, 2), round(x + w, 2), round(y + h + jy, 2)])
            x += w + R.uniform(4, 30)
        y += h * R.uniform(1.15, 1.8)
    order = list(range(len(words)))
    if R.random() < 0.3:
        R.shuffle(order)                         # thứ tự chữ đưa vào không theo dòng
    return [words[i] for i in order], [boxes[i] for i in order], total


DATES = ["20/09/2019", "14/08/2", "2020", "20/09", "2019", "1-2-21", "05.11.2020", "31/12/19", "Ngày:", "Giờ:", "14:35", "9h05",
         "08:30:12", "23.59", "14:35:", "HĐ:", "12/2020", "2021", "Ngay", "10:5", "7h", "2/3/2022", "15 : 20", "lúc"]
TOTALW = ["Tổng", "cộng:", "TONG", "CONG", "Thanh", "toán", "Tiền", "khách", "đưa", "Tiền", "thừa", "VAT", "107,0", ",000", "10,0",
          "000", "253", "000d", "87,0", "L000", "1000", "12.000", "45,000", "1.250.000", "123456789", "0912345678", "1,234.50",
          "vnd", "35000đ", "(99,000)", "total:", "Phải", "trả", "2,500,000", "1,000", "999"]
SELLER_NOISE = ["112", "p.", "p.cẩm", "q.", "q.pn", "co.op", "op", ".op", "k-", "k-park", "tel:", "Tel", "0945984733", "024",
                "MST", "Hotline", "028.3822.1234", "ĐT:", "Số", "5", "1234.", "Quảng", "Ninh", "Hà", "Nội", "đường", "đường"]


def cases():
    out = []
    for _ in range(1500):
        w, b, total = receipt()
        out.append({"fn": "extract_items", "words": w, "boxes": b})
        out.append({"fn": "group_lines", "words": w, "boxes": b})
        out.append({"fn": "rule_total", "words": w})
        out.append({"fn": "read_datetime", "words": w})
        res = LI.extract_items(w, b)
        out.append({"fn": "check_items", "items": res["items"], "totals": res["summary_amounts"] + [total, None, 0]})
    for _ in range(1500):
        out.append({"fn": "datetime_candidates", "words": [ch(DATES) for _ in range(R.randint(1, 7))]})
        tw = [ch(TOTALW) for _ in range(R.randint(1, 9))]
        out.append({"fn": "amount_candidates", "words": tw})
        out.append({"fn": "rule_total", "words": tw})
        out.append({"fn": "merge_split_amount", "head": ch(TOTALW), "tail": ch(TOTALW)})
        out.append({"fn": "normalize_amount", "text": ch(TOTALW + ["", "abc", "1.250.000 VND", "27.000đ", "100,000.00"])})
        txt = " ".join(ch(DATES) for _ in range(R.randint(1, 4)))
        out.append({"fn": "normalize_date", "text": txt})
        out.append({"fn": "extract_time", "text": txt})
        s = " ".join([ch(SELLERS)] + [ch(SELLER_NOISE) for _ in range(R.randint(0, 5))])
        out.append({"fn": "clean_field", "field": ch(["SELLER", "ADDRESS", "TOTAL_COST"]), "text": s})
        out.append({"fn": "fold", "text": s})
        out.append({"fn": "plain", "text": s})
    for line in ["Sữa 2 x 36 .500 73,000", "Giảm -3 .300", "1 Coca 24 000", "8, 000", "110,000", "Mì 12 HOP 6,500 78,000"]:
        out.append({"fn": "parse_amounts", "line": line})
    return out


PY = {
    "extract_items": lambda c: LI.extract_items(c["words"], c["boxes"]),
    "check_items": lambda c: LI.check_items(c["items"], c["totals"]),
    "parse_amounts": lambda c: [list(x) for x in LI.parse_amounts(c["line"])],
    "group_lines": lambda c: LI.group_lines(c["words"], c["boxes"]),
    "normalize_date": lambda c: normalize_date(c["text"]),
    "extract_time": lambda c: DT.extract_time(c["text"]),
    "datetime_candidates": lambda c: DT.datetime_candidates(c["words"]),
    "read_datetime": lambda c: DT.read_datetime(c["words"]),
    "amount_candidates": lambda c: [list(x) for x in DE.amount_candidates(c["words"])],
    "rule_total": lambda c: RU.rule_total(c["words"]),
    "merge_split_amount": lambda c: DE.merge_split_amount(c["head"], c["tail"]),
    "normalize_amount": lambda c: normalize_amount(c["text"]),
    "clean_field": lambda c: FC.clean_field(c["field"], c["text"]),
    "fold": lambda c: RU.fold(c["text"]),
    "plain": lambda c: LI.plain(c["text"]),
}


def canon(x):
    return json.loads(json.dumps(x, sort_keys=True, ensure_ascii=False))


def main():
    cs = cases()
    py = [canon(PY[c["fn"]](c)) for c in cs]
    js = json.loads(subprocess.run(["node", os.path.join(HERE, "rules_js.js")], input=json.dumps(cs, ensure_ascii=False),
                                   capture_output=True, text=True, encoding="utf-8", check=True).stdout)
    js = [canon(x) for x in js]
    bad = [(c, p, j) for c, p, j in zip(cs, py, js) if p != j]
    by_fn = {}
    for c in cs:
        by_fn[c["fn"]] = by_fn.get(c["fn"], 0) + 1
    if not bad:
        print(f"KHỚP: JS và Python cho cùng kết quả trên {len(cs)} ca")
        print("  " + ", ".join(f"{k} {v}" for k, v in sorted(by_fn.items())))
        n_items = sum(1 for c, p in zip(cs, py) if c["fn"] == "extract_items" and p["items"])
        n_ver = sum(1 for c, p in zip(cs, py) if c["fn"] == "check_items" and p["status"] == "verified")
        print(f"  (có dòng hàng: {n_items} hóa đơn, 'verified': {n_ver})")
        return
    print(f"LỆCH {len(bad)}/{len(cs)} ca. 5 ca đầu:")
    for c, p, j in bad[:5]:
        print(json.dumps({k: v for k, v in c.items() if k != "boxes"}, ensure_ascii=False)[:400])
        print("  Python:", json.dumps(p, ensure_ascii=False)[:400])
        print("  JS    :", json.dumps(j, ensure_ascii=False)[:400])
    sys.exit(1)


if __name__ == "__main__":
    main()
