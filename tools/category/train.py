"""Train bộ phân loại danh mục chi tiêu và xuất cho app.

Mô hình: TF-IDF n-gram ký tự (2–4, theo từng từ) + hồi quy logistic nhiều lớp.
Cách tách n-gram (analyzer="char_wb") và cách tính (sublinear tf, idf, chuẩn hoá L2)
khớp đúng với hàm categorize() trong www/receipt-engine.js, nên xuất ra JSON là chạy được ngay.

Chạy (sau gen_data.py):
    python tools/category/train.py            # train + đánh giá + ghi www/receipt_model.json, www/receipt_lexicon.json
    python tools/category/train.py --dry-run  # chỉ train + đánh giá, không ghi file
"""
import argparse, csv, json, os, re, sys, unicodedata

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report, confusion_matrix, f1_score
from sklearn.model_selection import cross_val_score

from seed import CATEGORIES, SEED

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
MODEL_JSON = os.path.join(ROOT, "www", "receipt_model.json")
LEX_JSON = os.path.join(ROOT, "www", "receipt_lexicon.json")
OLD_CAT = os.path.join(HERE, "data", "cat_v1.json")    # bản model cũ (5 lớp) để so sánh

MAX_FEATURES = 5000
THRESHOLD = 0.45          # giống receipt-engine.js: dưới ngưỡng thì dùng từ điển


# ---- tiền xử lý: PHẢI giống fold()/normTok() trong receipt-engine.js ----
def fold(s):
    s = unicodedata.normalize("NFD", str(s).lower())
    s = "".join(c for c in s if not ("̀" <= c <= "ͯ")).replace("đ", "d")
    return re.sub(r"\s+", " ", s).strip()


def norm_tok(s):
    return re.sub(r"[^a-z0-9]+", " ", fold(s)).strip()


def load(path):
    with open(path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    return [norm_tok(r["text"]) for r in rows], [r["label"] for r in rows], [r["text"] for r in rows]


# ---- mô phỏng lại đúng logic JS để đánh giá cả "model + từ điển" ----
def js_model_proba(cat, texts):
    vocab, idf = cat["vocab"], np.array(cat["idf"])
    coef, b = np.array(cat["coef"]), np.array(cat["intercept"])
    an = TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4), lowercase=False).build_analyzer()
    out = []
    for t in texts:
        cnt = {}
        for g in an(t):
            if g in vocab:
                cnt[vocab[g]] = cnt.get(vocab[g], 0) + 1
        x = {j: (1 + np.log(v)) * idf[j] for j, v in cnt.items()}
        nrm = np.sqrt(sum(v * v for v in x.values())) or 1.0
        z = b.copy()
        for j, v in x.items():
            z += coef[:, j] * v / nrm
        e = np.exp(z - z.max())
        out.append(e / e.sum())
    return np.array(out)


def lex_predict(lex, text):
    pad = " " + text + " "
    sc = {k: 0 for k in lex["categories"]}
    for cat, kws in lex["cat_kw"].items():
        for kw, w in kws:
            if " " + kw + " " in pad:
                sc[cat] += w
    best = max(sc, key=lambda k: sc[k])          # giống reduce() trong JS: hoà điểm thì lấy mục đứng trước
    best = next(k for k in lex["categories"] if sc[k] == sc[best])
    return best if sc[best] > 0 else "other"


def pipeline(cat, lex, texts):
    pr = js_model_proba(cat, texts)
    res = []
    for t, p in zip(texts, pr):
        k = int(p.argmax())
        res.append(cat["classes"][k] if p[k] >= THRESHOLD else lex_predict(lex, t))
    return res


# ---- từ điển: gộp từ seed vào receipt_lexicon.json ----
REMAP = {"pharmacity": "health", "long chau": "health", "nha thuoc": "health", "benh vien": "health",
         "phong kham": "health"}


def build_lexicon(lex):
    lex = json.loads(json.dumps(lex))
    old = lex["cat_kw"]
    new = {c: [] for c in CATEGORIES}
    seen = set()
    for c, kws in old.items():                      # giữ nguyên từ khoá cũ, chuyển một số sang danh mục mới
        for kw, w in kws:
            c2 = REMAP.get(kw, c)
            if c2 not in new:                       # danh mục đã bỏ (vd. "bills") -> "other"
                c2 = "other"
            new[c2].append([kw, w]); seen.add(kw)
    for c in CATEGORIES:                            # thêm từ khoá từ seed
        for kw in SEED[c]["items"]:
            k = norm_tok(kw)
            if k and k not in seen:
                new[c].append([k, 2 if " " in k else 1]); seen.add(k)
        for kw in SEED[c]["brands"]:
            k = norm_tok(kw)
            if k and k not in seen and len(k) > 2:
                new[c].append([k, 3]); seen.add(k)
    lex["categories"] = CATEGORIES
    lex["cat_kw"] = new
    lex["version"] = int(lex.get("version", 1)) + 1
    return lex


def export(vec, clf):
    vocab = {g: int(i) for g, i in vec.vocabulary_.items()}
    return {
        "classes": [str(c) for c in clf.classes_],
        "vocab": vocab,
        "idf": [round(float(v), 4) for v in vec.idf_],
        "coef": [[round(float(v), 4) for v in row] for row in clf.coef_],
        "intercept": [round(float(v), 4) for v in clf.intercept_],
    }


def report(name, y, p):
    labels = CATEGORIES
    print(f"\n=== {name} ===  accuracy {np.mean(np.array(y) == np.array(p)):.3f}   macro-F1 {f1_score(y, p, labels=labels, average='macro', zero_division=0):.3f}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--C", type=float, default=None, help="bỏ trống để chọn bằng cross-validation trên tập train")
    a = ap.parse_args()

    Xtr, ytr, _ = load(os.path.join(HERE, "data", "train.csv"))
    Xte, yte, raw = load(os.path.join(HERE, "test_set.csv"))
    print(f"train {len(Xtr)} câu, test {len(Xte)} câu, {len(CATEGORIES)} danh mục")

    vec = TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4), lowercase=False, sublinear_tf=True, max_features=MAX_FEATURES)
    Vtr = vec.fit_transform(Xtr)

    C = a.C
    if C is None:                                   # chọn C chỉ bằng tập train (không nhìn test)
        best = None
        for c in (1, 3, 10, 30):
            s = cross_val_score(LogisticRegression(C=c, max_iter=3000, class_weight="balanced"), Vtr, ytr, cv=4, scoring="f1_macro").mean()
            print(f"  C={c:<3} CV macro-F1 {s:.4f}")
            if best is None or s > best[0] + 1e-3:
                best = (s, c)
        C = best[1]
    clf = LogisticRegression(C=C, max_iter=3000, class_weight="balanced").fit(Vtr, ytr)
    cat = export(vec, clf)
    print(f"chọn C={C}, vocab {len(cat['vocab'])}")

    with open(LEX_JSON, encoding="utf-8") as f:
        lex_old = json.load(f)
    lex_new = build_lexicon(lex_old)

    # model cũ để so sánh
    with open(MODEL_JSON, encoding="utf-8") as f:
        model = json.load(f)
    if not os.path.exists(OLD_CAT) and len(model["cat"]["classes"]) == 5:
        with open(OLD_CAT, "w", encoding="utf-8") as f:
            json.dump(model["cat"], f, ensure_ascii=False)
    if os.path.exists(OLD_CAT):
        with open(OLD_CAT, encoding="utf-8") as f:
            old = json.load(f)
        report("CŨ: model 5 lớp + từ điển cũ", yte, pipeline(old, lex_old, Xte))

    p_model = [cat["classes"][int(i)] for i in js_model_proba(cat, Xte).argmax(1)]
    report("MỚI: chỉ model", yte, p_model)
    p_new = pipeline(cat, lex_new, Xte)
    report("MỚI: model + từ điển (như trong app)", yte, p_new)
    # Câu test không chứa nguyên cụm nào trong seed: đo khả năng đoán từ chưa gặp (con số đáng tin hơn)
    kws = [norm_tok(k) for c in SEED for k in SEED[c]["items"] + SEED[c]["brands"]]
    uns = [i for i, t in enumerate(Xte) if not any(" " + k + " " in " " + t + " " for k in kws)]
    if uns:
        ok = sum(yte[i] == p_new[i] for i in uns)
        print(f"Câu có từ chưa gặp trong seed: đúng {ok}/{len(uns)} ({ok / len(uns):.0%})")
    print(classification_report(yte, p_new, labels=CATEGORIES, zero_division=0, digits=3))
    print("Ma trận nhầm lẫn (hàng = đúng, cột = đoán):", CATEGORIES)
    for c, row in zip(CATEGORIES, confusion_matrix(yte, p_new, labels=CATEGORIES)):
        print(f"  {c:<7}", " ".join(f"{v:3d}" for v in row))
    wrong = [(r, y, p) for r, y, p in zip(raw, yte, p_new) if y != p]
    if wrong:
        print(f"\nCâu sai ({len(wrong)}):")
        for r, y, p in wrong:
            print(f"  {r!r:45} đúng={y:<7} đoán={p}")

    if a.dry_run:
        return
    model["cat"] = cat
    model["version"] = int(model.get("version", 1)) + 1
    with open(MODEL_JSON, "w", encoding="utf-8") as f:
        json.dump(model, f, ensure_ascii=False, separators=(",", ":"))
    with open(LEX_JSON, "w", encoding="utf-8") as f:
        json.dump(lex_new, f, ensure_ascii=False, indent=1)
    print(f"\nĐã ghi {os.path.relpath(MODEL_JSON, ROOT)} ({os.path.getsize(MODEL_JSON) // 1024} KB) và {os.path.relpath(LEX_JSON, ROOT)}")


if __name__ == "__main__":
    sys.exit(main())
