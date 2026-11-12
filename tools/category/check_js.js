// Kiểm tra www/receipt-engine.js (đúng code chạy trong app) cho ra cùng kết quả với train.py trên bộ test.
// Chạy: node tools/category/check_js.js
const fs = require("fs"), path = require("path");
const root = path.join(__dirname, "..", "..");
global.fetch = undefined;                                   // không cho engine tự tải file
const E = require(path.join(root, "www", "receipt-engine.js"));
E.init(JSON.parse(fs.readFileSync(path.join(root, "www", "receipt_lexicon.json"), "utf8")),
       JSON.parse(fs.readFileSync(path.join(root, "www", "receipt_model.json"), "utf8")));
const rows = fs.readFileSync(path.join(__dirname, "test_set.csv"), "utf8").trim().split(/\r?\n/).slice(1)
  .map(l => { const i = l.lastIndexOf(","); return [l.slice(0, i), l.slice(i + 1)]; });
let ok = 0;
const out = rows.map(([t, y]) => { const p = E._t.categorize(t); if (p === y) ok++; return `${t}\t${y}\t${p}`; });
fs.writeFileSync(path.join(__dirname, "data", "js_pred.tsv"), out.join("\n"));
console.log(`JS: đúng ${ok}/${rows.length} (${(100 * ok / rows.length).toFixed(1)}%)`);
console.log("bút chì ->", E._t.categorize("bút chì"), "| thuốc ->", E._t.categorize("thuốc"), "| tiền nước ->", E._t.categorize("tiền nước 80k"), "| nước mía ->", E._t.categorize("nước mía"));
