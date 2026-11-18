// Chạy www/receipt-rules.js trên các ca thử (JSON từ stdin), in kết quả JSON để check_rules.py so với bản Python.
const path = require("path");
const R = require(path.join(__dirname, "..", "..", "www", "receipt-rules.js"));
const cases = JSON.parse(require("fs").readFileSync(0, "utf8"));
const run = {
  extract_items: c => R.extractItems(c.words, c.boxes),
  check_items: c => R.checkItems(c.items, c.totals),
  parse_amounts: c => R.parseAmounts(c.line),
  group_lines: c => R.groupLines(c.words, c.boxes),
  normalize_date: c => R.normalizeDate(c.text),
  extract_time: c => R.extractTime(c.text),
  datetime_candidates: c => R.datetimeCandidates(c.words),
  read_datetime: c => R.readDatetime(c.words),
  amount_candidates: c => R.amountCandidates(c.words),
  rule_total: c => R.ruleTotal(c.words),
  merge_split_amount: c => R.mergeSplitAmount(c.head, c.tail),
  normalize_amount: c => R.normalizeAmount(c.text),
  clean_field: c => R.cleanField(c.field, c.text),
  fold: c => R.fold(c.text),
  plain: c => R.plain(c.text),
};
const out = cases.map(c => { try { return run[c.fn](c); } catch (e) { return "LỖI JS: " + e.message; } });
process.stdout.write(JSON.stringify(out));
