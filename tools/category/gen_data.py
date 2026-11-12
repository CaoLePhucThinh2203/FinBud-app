"""Sinh dữ liệu train (câu ghi chú chi tiêu + nội dung thông báo ngân hàng + đoạn hóa đơn) từ seed.py.

Chạy:  python tools/category/gen_data.py   ->  tools/category/data/train.csv
Kết quả cố định theo SEED_RANDOM nên chạy lại cho cùng dữ liệu.
"""
import csv, os, random, sys, unicodedata

from seed import AMOUNTS, CATEGORIES, SEED, WHO

sys.stdout.reconfigure(encoding="utf-8")

SEED_RANDOM = 2203
PER_CAT_NOTES = 1400      # câu kiểu "ăn bún bò 35k"
PER_CAT_BANK = 250        # kiểu nội dung chuyển khoản / thông báo
PER_CAT_RECEIPT = 120     # đoạn chữ OCR từ hóa đơn

HERE = os.path.dirname(os.path.abspath(__file__))
R = random.Random(SEED_RANDOM)


def fold(s):
    s = unicodedata.normalize("NFD", s.lower())
    return "".join(c for c in s if not ("̀" <= c <= "ͯ")).replace("đ", "d")


def typo(s, p=0.08):
    """Thỉnh thoảng gõ sót/lặp một ký tự, giống người gõ vội."""
    if R.random() > p or len(s) < 5:
        return s
    i = R.randrange(1, len(s) - 1)
    return s[:i] + s[i + 1:] if R.random() < 0.6 else s[:i] + s[i] + s[i:]


def note(cat):
    d = SEED[cat]
    thing = R.choice(d["items"]) if R.random() < 0.82 else R.choice(d["brands"])
    if R.random() < 0.25 and d["brands"]:
        thing = f"{thing} {R.choice(['ở', 'tại', ''])} {R.choice(d['brands'])}".replace("  ", " ")
    verb = R.choice(d["verbs"]) if R.random() < 0.55 else ""
    amt, who = R.choice(AMOUNTS), R.choice(WHO)
    pattern = R.choice([
        "{v} {t} {a}", "{t} {a}", "{a} {t}", "{v} {t}", "{t}", "{v} {t} hết {a}", "{w} {v} {t} {a}",
        "{t} {w} {a}", "tiền {t} {a}", "{v} {t} {w}",
    ])
    s = pattern.format(v=verb, t=thing, a=amt, w=who)
    s = " ".join(s.split())
    if R.random() < 0.3:
        s = fold(s)                       # người dùng gõ không dấu
    return typo(s)


def bank(cat):
    d = SEED[cat]
    thing = fold(R.choice(d["items"] + d["brands"])).upper()
    pre = R.choice(["THANH TOAN", "TT", "CK", "QR", "PAY", "THANH TOAN QR", "CHUYEN TIEN", "", "MBVCB", "FT"])
    ref = R.choice(["", str(R.randrange(10 ** 5, 10 ** 9)), "-" + str(R.randrange(1000, 99999))])
    return " ".join(f"{pre} {thing} {ref}".split())


RECEIPT_TAIL = ["tong cong", "tong tien", "thanh tien", "tien mat", "tien thoi lai", "vat", "cam on quy khach",
                "hen gap lai", "so luong", "don gia", "hoa don ban le", "ngay", "thu ngan"]


def receipt(cat):
    d = SEED[cat]
    head = R.choice(d["brands"])
    lines = [head.upper(), "dc: " + R.choice(["123 le loi", "45 nguyen trai", "q1 tp hcm", "cau giay ha noi", ""])]
    for _ in range(R.randint(1, 5)):
        lines.append(f"{R.choice(d['items'])} {R.randint(1, 3)} {R.randrange(5, 300) * 1000:,}")
    lines += R.sample(RECEIPT_TAIL, R.randint(2, 5))
    return " ".join(fold(" ".join(lines)).split())


def main():
    rows = []
    for cat in CATEGORIES:
        rows += [(note(cat), cat) for _ in range(PER_CAT_NOTES)]
        rows += [(bank(cat), cat) for _ in range(PER_CAT_BANK)]
        if cat not in ("bills", "other"):          # hai nhóm này ít khi có hóa đơn in
            rows += [(receipt(cat), cat) for _ in range(PER_CAT_RECEIPT)]
    R.shuffle(rows)
    os.makedirs(os.path.join(HERE, "data"), exist_ok=True)
    out = os.path.join(HERE, "data", "train.csv")
    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["text", "label"])
        w.writerows(rows)
    print(f"{len(rows)} dòng -> {out}")


if __name__ == "__main__":
    main()
