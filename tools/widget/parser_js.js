// Chạy bản JS (parseAmt + classifyRx trong www/index.html, categorize trong www/receipt-engine.js) trên danh sách câu,
// in TSV cùng định dạng với ParserCheck.java để so sánh. Dùng qua tools/widget/check_parser.sh
const fs = require("fs"), path = require("path");
const root = process.argv[2], lines = fs.readFileSync(process.argv[3], "utf8").split(/\r?\n/).filter(Boolean);
global.fetch = undefined;
const E = require(path.join(root, "www", "receipt-engine.js"));
E.init(JSON.parse(fs.readFileSync(path.join(root, "www", "receipt_lexicon.json"), "utf8")),
       JSON.parse(fs.readFileSync(path.join(root, "www", "receipt_model.json"), "utf8")));

// Lấy đúng các hàm trong index.html thay vì chép lại, để kiểm tra code thật của app.
const html = fs.readFileSync(path.join(root, "www", "index.html"), "utf8");
const grab = re => { const m = html.match(re); if (!m) throw new Error("Không tìm thấy " + re); return m[0]; };
const src = [
  grab(/const num=x=>[^\n]*/),
  grab(/function classifyRx\(s\)\{[\s\S]*?return"other"\}/),
  grab(/const W=\{[^\n]*/),
  grab(/function parseAmt\(t\)\{[\s\S]*?\n return\{a:0,n:s\}\}/),
].join("\n");
const { parseAmt, classifyRx } = new Function(src.replace(/^const num=/, "var num=") + "\nreturn{parseAmt,classifyRx}")();

const out = lines.map(t => {
  const p = parseAmt(t), n = (p.n || "Chi tiêu").replace(/^./, x => x.toUpperCase());
  return [t, p.a, n, E._t.categorize(t), classifyRx(t)].join("\t");
});
process.stdout.write(out.join("\n") + "\n");
