/* FinBud – các luật đọc hóa đơn chuyển từ đồ án QuetHoaDon (github.com/CaoLePhucThinh2203/QuetHoaDon, src/extraction).
 * Thuần luật, không mô hình; nhận chữ + khung chữ (ML Kit) và trả về:
 *   - dòng hàng: tên, số lượng, đơn giá, thành tiền (line_items.py), kiểm tra tổng các dòng = tổng in trên hóa đơn (check_items)
 *   - ngày giờ: ghép ngày bị OCR tách, lấy giờ cạnh ngày (datetime_rules.py)
 *   - tổng tiền theo từ khoá "tổng cộng / thanh toán…", ghép số bị tách ("107,0" + ",000") (rules.py, decode.py)
 *   - làm sạch tên cửa hàng / địa chỉ (field_cleanup.py)
 * PHẢI cho kết quả giống hệt bản Python; kiểm tra bằng tools/receipt-rules/check_rules.sh (cần bản clone QuetHoaDon).
 * Tên hàm giữ như bản Python (snake_case -> camelCase) để dễ đối chiếu khi QuetHoaDon cập nhật. */
(function () {
  // ---------------------------------------------------------------- chuẩn hoá chữ
  const MN = /\p{Mn}/gu;
  /** line_items.plain: chữ thường, bỏ dấu, chỉ chữ + số, một dấu cách. */
  function plain(text) {
    let t = String(text).toLowerCase().replace(/đ/g, "d").replace(/Đ/g, "d").normalize("NFD").replace(MN, "");
    return t.replace(/[^a-z0-9]+/g, " ").replace(/\s+/g, " ").trim();
  }
  /** rules.fold: chữ thường, bỏ dấu, chỉ chữ + số, không dấu cách ("Tổng cộng:" -> "tongcong"). */
  function fold(text) {
    const s = String(text || "").toLowerCase().replace(/đ/g, "d").normalize("NFD").replace(MN, "");
    return s.replace(/[^a-z0-9]/g, "");
  }
  const has = (p, words) => { const padded = " " + p + " "; return words.some(w => padded.includes(" " + w + " ")); };
  const pySplit = s => String(s).split(/\s+/).filter(Boolean);                 // str.split() của Python
  const alphaCount = s => (String(s).match(/\p{L}/gu) || []).length;          // số ký tự c.isalpha()
  /** str.strip(chars) của Python */
  function strip(s, chars) {
    let a = 0, b = s.length;
    while (a < b && chars.includes(s[a])) a++;
    while (b > a && chars.includes(s[b - 1])) b--;
    return s.slice(a, b);
  }

  // ---------------------------------------------------------------- dòng hàng (line_items.py)
  const SEP = "\\s?[.,]\\s?";
  const AMOUNT = new RegExp("(?<![\\d])(\\d{1,3}(?:" + SEP + "\\d{3})+|\\d{1,3}\\s\\d00(?!\\d))(?:" + SEP + "0{2}(?!\\d))?", "g");
  const DECIMAL_TAIL = new RegExp(SEP + "0{2}$");
  const BARCODE = /(?<![\p{L}\p{N}_])\d{6,14}(?![\p{L}\p{N}_])/gu;           // \b\d{6,14}\b của Python (ranh giới Unicode)
  const SMALL_INT = /(?<![\d.,])(\d{1,3})(?![\d.,])/g;
  const HEADER_WORDS = ["ten hang", "ten mon", "mat hang", "hang hoa", "sl", "so luong", "don gia", "thanh tien", "t tien", "dvt", "gia", "tt"];
  const END_WORDS = ["tong", "cong", "thanh toan", "phai tra", "phai t toan"];
  const TOP_WORDS = ["hoa don", "ngay", "gio", "thu ngan", "quay", "so hd", "phieu", "nvbh", "ban hang"];
  const SKIP_START = ["gia dang ky", "gia niem yet", "gia goc"];
  const DISCOUNT_START = ["km", "khuyen mai", "giam", "chiet khau", "ck"];
  const NOT_A_NAME = ["ten mon", "ten hang", "mat hang", "thanh tien", "so luong", "don gia", "tong", "cong"];
  const MAX_UNCHECKED = 2000000;
  const UNITS = new Set(["goi", "hop", "lo", "ch", "nt", "kg", "chai", "cai", "thung", "lon", "tui", "kien", "cap", "bich", "g", "vnd", "d", "vat", "x"]);

  /** Gom chữ thành dòng theo toạ độ dọc (tâm chữ lệch < ~0,6 chiều cao chữ so với trung bình của dòng). */
  function groupLines(words, boxes) {
    const items = words.map((w, i) => [w, boxes[i]]).sort((a, b) => (a[1][1] + a[1][3]) / 2 - (b[1][1] + b[1][3]) / 2);
    const lines = [];
    let cur = [];
    for (const [w, b] of items) {
      const cy = (b[1] + b[3]) / 2;
      if (cur.length) {
        const meanY = cur.reduce((s, c) => s + (c[1][1] + c[1][3]) / 2, 0) / cur.length;
        const meanH = cur.reduce((s, c) => s + (c[1][3] - c[1][1]), 0) / cur.length;
        if (Math.abs(cy - meanY) < Math.max(8.0, 0.6 * meanH)) { cur.push([w, b]); continue; }
        lines.push(cur);
      }
      cur = [[w, b]];
    }
    if (cur.length) lines.push(cur);
    return lines.map(line => line.slice().sort((a, b) => a[1][0] - b[1][0]).map(x => x[0]).join(" "));
  }

  /** Số tiền trong một dòng: [giá trị, vị trí đầu, vị trí cuối, là số âm]; "-3 .300" là giảm giá 3300. */
  function parseAmounts(line) {
    const out = [];
    for (const m of line.matchAll(AMOUNT)) {
      const digits = m[0].replace(DECIMAL_TAIL, "").replace(/\D/g, "");
      if (!digits) continue;
      const before = line.slice(Math.max(0, m.index - 2), m.index);
      out.push([parseInt(digits, 10), m.index, m.index + m[0].length, before.replace(/\s+$/, "").endsWith("-")]);
    }
    return out;
  }

  function smallInts(line, amounts) {
    const out = [];
    for (const m of line.matchAll(SMALL_INT)) {
      const v = parseInt(m[1], 10);
      if (v > 0 && v < 1000 && !amounts.some(([, s, e]) => s <= m.index && m.index < e)) out.push(v);
    }
    return out;
  }

  function nameOf(line, amounts) {
    let text = line;
    for (const [, s, e] of amounts.slice().sort((a, b) => b[1] - a[1])) text = text.slice(0, s) + " " + text.slice(e);
    text = text.replace(BARCODE, " ");
    const toks = pySplit(text);
    const junk = t => /^[\d.,:/x+\-]+$/.test(t) || plain(t) === "" || UNITS.has(plain(t));
    while (toks.length && junk(toks[0])) toks.shift();
    while (toks.length && junk(toks[toks.length - 1])) toks.pop();
    const name = strip(toks.join(" "), " .,:;-");
    return alphaCount(name) >= 3 ? name : "";
  }

  /** Số lượng, đơn giá, thành tiền của một dòng hàng, chọn sao cho số lượng × đơn giá = thành tiền. */
  function quantity(amounts, smalls) {
    const values = amounts.map(a => a[0]), total = values[values.length - 1];
    if (values.length === 1) {
      if (smalls.includes(1) || !smalls.length) return { quantity: 1, unit_price: total, amount: total, line_ok: true };
      const q = smalls[0], ok = q > 0 && total % q === 0;
      return { quantity: q, unit_price: ok ? Math.floor(total / q) : total, amount: total, line_ok: ok };
    }
    for (const unit of values.slice(0, -1).reverse()) {
      for (const q of smalls) if (q * unit === total) return { quantity: q, unit_price: unit, amount: total, line_ok: true };
      if (unit === total) return { quantity: 1, unit_price: unit, amount: total, line_ok: true };
      if (unit && total % unit === 0 && Math.floor(total / unit) > 1 && Math.floor(total / unit) < 1000)
        return { quantity: Math.floor(total / unit), unit_price: unit, amount: total, line_ok: true };
    }
    return { quantity: smalls.length ? smalls[0] : 1, unit_price: values[values.length - 2], amount: total, line_ok: false };
  }

  function headerIndex(lp) {
    for (let i = 0; i < lp.length; i++) if (HEADER_WORDS.filter(w => (" " + lp[i] + " ").includes(" " + w + " ")).length >= 2) return i;
    return null;
  }
  function endIndex(lp, start) {
    for (let i = start; i < lp.length; i++) if (has(lp[i], END_WORDS)) return i;
    return null;
  }

  /** Các dòng hàng của hóa đơn: {items, summary_amounts, header_found, end_found} (extract_items). */
  function extractItems(words, boxes) {
    const lines = groupLines(words, boxes), lp = lines.map(plain);
    const header = headerIndex(lp);
    let start = header !== null ? header + 1 : 0;
    const end = endIndex(lp, start);
    if (header === null && end !== null) {
      const top = [];
      for (let i = 0; i < end; i++) if (has(lp[i], TOP_WORDS)) top.push(i);
      start = top.length ? Math.max(...top) + 1 : 0;
    }
    const stop = end !== null ? end : lines.length, items = [];
    let pending = "";
    for (let i = start; i < stop; i++) {
      const line = lines[i], p = lp[i];
      if (SKIP_START.some(s => p.startsWith(s))) continue;
      const amounts = parseAmounts(line);
      if (!amounts.length) {
        if (alphaCount(line) >= 3 && !has(p, TOP_WORDS)) pending = nameOf(line, []) || pending;
        continue;
      }
      const parsed = quantity(amounts, smallInts(line, amounts));
      let name = nameOf(line, amounts);
      if (plain(name).startsWith("gia ban")) name = "";
      if (name && NOT_A_NAME.some(w => plain(name).startsWith(w))) continue;
      name = name || pending;
      if (!parsed.line_ok && Math.abs(parsed.amount) > MAX_UNCHECKED) continue;
      const discount = DISCOUNT_START.some(d => p.startsWith(d + " ") || p === d) || amounts[amounts.length - 1][3];
      if (discount) {
        const a = parsed.amount;
        Object.assign(parsed, { amount: -a, quantity: 1, unit_price: -a, line_ok: true });
      } else if (!name) {
        pending = "";
        continue;
      }
      items.push({ name, ...parsed, discount: !!discount });
      pending = "";
    }
    const summary = [];
    if (end !== null) for (let j = end; j < Math.min(lines.length, end + 8); j++) if (has(lp[j], END_WORDS)) summary.push(...parseAmounts(lines[j]).map(a => a[0]));
    return { items, summary_amounts: summary, header_found: header !== null, end_found: end !== null };
  }

  /** So tổng các dòng hàng với các số có thể là tổng tiền; "verified" khi bằng một trong số đó (check_items). */
  function checkItems(items, totals) {
    const sum = items.reduce((s, it) => s + it.amount, 0), gross = items.filter(it => !it.discount).reduce((s, it) => s + it.amount, 0);
    const wanted = totals.filter(t => t);
    return { sum, sum_without_discounts: gross, totals: wanted,
      status: items.length && (wanted.includes(sum) || wanted.includes(gross)) ? "verified" : "unverified",
      lines_ok: items.filter(it => it.line_ok).length, n_items: items.length };
  }

  // ---------------------------------------------------------------- ngày giờ (datetime_rules.py, field_eval.normalize_date)
  const DATE_RE = /(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4}|\d{2})/;
  const DATE_SPLIT = /(\d{1,2})[/\-.](\d{1,2})[/\-.]\d\s*(20\d{2})/;
  const DATE_ANY = /\d{1,2}\s*[/\-.]\s*\d{1,2}\s*[/\-.]\s*\d{2,4}/g;
  const TIME_RE = /(?<!\d)([01]?\d|2[0-3])\s*[:h]\s*([0-5]\d)(?:\s*[:.]\s*[0-5]\d)?(?!\d)/i;
  const DAY_MONTH = /^\D*?(\d{1,2})[/\-.](\d{1,2})\D*$/;
  const YEAR_WORD = /^\D*(20[123]\d)\D*$/;
  const p2 = n => String(n).padStart(2, "0");

  /** "dd/mm/yyyy" của ngày đầu tiên trong chữ (năm 2 số thành 20yy), hoặc null. */
  function normalizeDate(text) {
    const m = String(text || "").match(DATE_RE) || String(text || "").match(DATE_SPLIT);
    if (!m) return null;
    const year = m[3].length === 2 ? "20" + m[3] : m[3];
    return p2(parseInt(m[1], 10)) + "/" + p2(parseInt(m[2], 10)) + "/" + year;
  }

  /** "hh:mm" của phần giống giờ đầu tiên (đã bỏ ngày), hoặc null. */
  function extractTime(text) {
    const s = String(text || "").replace(DATE_ANY, " ").replace(new RegExp(DATE_SPLIT.source, "g"), " ");
    const m = s.match(TIME_RE);
    return m ? p2(parseInt(m[1], 10)) + ":" + m[2] : null;
  }

  function dateAt(words, i) {
    let d = normalizeDate(words[i]);
    if (d) return [d, i + 1];
    if (i + 1 < words.length && normalizeDate(words[i + 1]) === null) {
      d = normalizeDate(words[i] + " " + words[i + 1]);                    // "14/08/2" + "2020"
      if (d) return [d, i + 2];
    }
    const m = words[i].match(DAY_MONTH);
    if (m && +m[1] >= 1 && +m[1] <= 31 && +m[2] >= 1 && +m[2] <= 12) {
      for (const j of [i + 1, i + 2]) {                                     // "20/09" + "2019" (cho phép 1 chữ chen giữa)
        if (j < words.length) {
          const y = words[j].match(YEAR_WORD);
          if (y) return [p2(parseInt(m[1], 10)) + "/" + p2(parseInt(m[2], 10)) + "/" + y[1], j + 1];
        }
      }
    }
    return null;
  }

  function timeNear(words, start, end, allowBefore) {
    const glued = extractTime(words.slice(start, end).join(" "));
    if (glued) return glued;
    const after = extractTime(words.slice(end, end + 4).join(" "));
    if (after || !allowBefore) return after;
    return extractTime(words.slice(Math.max(0, start - 2), start).join(" "));
  }

  function datetimeCandidates(words) {
    const out = [];
    let i = 0;
    while (i < words.length) {
      const found = dateAt(words, i);
      if (found === null) { i++; continue; }
      const [date, end] = found;
      out.push({ date, time: timeNear(words, i, end, !out.length), start: i, end });
      i = end;
    }
    return out;
  }

  /** "dd/mm/yyyy hh:mm" (hoặc chỉ ngày) của ngày đầu tiên tìm được, hoặc null (read_datetime). */
  function readDatetime(words) {
    const c = datetimeCandidates(words);
    if (!c.length) return null;
    return c[0].time ? c[0].date + " " + c[0].time : c[0].date;
  }

  // ---------------------------------------------------------------- tổng tiền (rules.py, decode.py, metrics.normalize_amount)
  const SEP_AMOUNT = /^\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{1,2})?$/;
  const CURRENCY_TAIL = /(vnđ|vnd|đồng|₫|đ|d)$/;
  const CURRENCY_HEAD = /^(vnđ|vnd|đ)/;
  const SPLIT_HEAD = /^(\d{1,3}(?:[.,]\d{3})*)(?:[.,]\d?)?$/;
  const SPLIT_TAIL = /^[.,]?(?:[l1i]000|\d{3})$/;
  const AMOUNT_FULL = /^(\d{1,3}(?:[.,]\d{3})+|\d+)(?:[.,]\d{1,2})?$/;
  const TOTAL_KEYS = ["tongcong", "tongtien", "tongthanhtoan", "thanhtoan", "tongso", "total", "phaitra", "canthanhtoan", "tongtt", "tongcongtien", "tong"];
  const EXCLUDE_KEYS = ["tienkhachdua", "khachdua", "tienthua", "thoilai", "tienmat", "chietkhau", "giamgia", "vat", "thue"];

  function normalizeAmount(text) {
    if (!text) return null;
    let s = String(text).normalize("NFC").toLowerCase().replace(/(vnđ|vnd|đồng|₫|đ)/g, "").replace(/[^0-9.,]/g, "");
    const m = s ? s.match(AMOUNT_FULL) : null;
    if (m) return parseInt(m[1].replace(/[.,]/g, ""), 10);
    const digits = s.replace(/\D/g, "");
    return digits ? parseInt(digits, 10) : null;
  }

  function cleanAmountToken(token) {
    let t = strip(String(token).trim().toLowerCase(), "()[]:;*");
    t = t.replace(CURRENCY_HEAD, "").replace(CURRENCY_TAIL, "");
    return strip(t, ".,");
  }

  /** Số tiền bị OCR tách làm hai chữ ("107,0" + ",000" -> 107000), hoặc null. */
  function mergeSplitAmount(head, tail) {
    const ht = cleanAmountToken(head), tt = cleanAmountToken(tail), h = ht.match(SPLIT_HEAD);
    if (!h || !SPLIT_TAIL.test(tt)) return null;
    const hasSep = /[.,]/.test(head + tail);
    if (tt.startsWith("1") && tt.length === 4 && !hasSep) return null;
    if (/[.,]\d{3}/.test(h[1]) && !(tail.trim().startsWith(",") || tail.trim().startsWith("."))) return null;
    return parseInt(h[1].replace(/[.,]/g, ""), 10) * 1000 + parseInt(tt.slice(-3), 10);
  }

  function parseAmountToken(token, minV = 1000, maxV = 500000000) {
    const t = cleanAmountToken(token);
    if (!(SEP_AMOUNT.test(t) || /^\d{4,7}$/.test(t))) return null;
    const v = normalizeAmount(t);
    if (v === null || !(minV <= v && v <= maxV)) return null;
    return [t, v];
  }

  /** Mọi số tiền hợp lý trong danh sách chữ: [đầu, cuối (không gồm), chữ, giá trị] (amount_candidates). */
  function amountCandidates(words, minV = 1000, maxV = 500000000) {
    const out = [];
    let i = 0;
    while (i < words.length) {
      if (i + 1 < words.length) {
        const merged = mergeSplitAmount(words[i], words[i + 1]);
        if (merged !== null && minV <= merged && merged <= maxV) {
          out.push([i, i + 2, merged.toLocaleString("en-US"), merged]);
          i += 2;
          continue;
        }
      }
      const p = parseAmountToken(words[i], minV, maxV);
      if (p) out.push([i, i + 1, p[0], p[1]]);
      i++;
    }
    return out;
  }

  function keywordAt(words, i, keys) {
    if (!fold(words[i])) return false;
    const joined = words.slice(i, i + 3).map(fold).join("");
    return keys.some(k => joined.startsWith(k));
  }

  /** Số lớn nhất trong 6 chữ sau từ khoá tổng tiền (không tính sau "tiền khách đưa"…), dạng chữ, hoặc null (rule_total). */
  function ruleTotal(words, window = 6) {
    const cands = amountCandidates(words);
    let best = null;
    for (let i = 0; i < words.length; i++) {
      if (!keywordAt(words, i, TOTAL_KEYS) || keywordAt(words, i, EXCLUDE_KEYS)) continue;
      for (const [start, , text, value] of cands) if (i < start && start <= i + window && (best === null || value > best[0])) best = [value, text];
    }
    return best ? best[1] : null;
  }

  // ---------------------------------------------------------------- làm sạch tên cửa hàng / địa chỉ (field_cleanup.py)
  const TRAILING_LABELS = new Set(["tel", "dt", "dttel", "sdt", "dienthoai", "email", "mst", "ma", "maso", "masothue", "so", "tid",
    "hotline", "hot", "terminal", "fax", "web", "website", "storeid", "tat", "el"]);
  const PHONE = /^[(+]?\d[\d.\-() ]{6,}\.?$/;
  const AREA_CODE = /^0\d{2,3}\.?$/;
  const phoneLike = t => PHONE.test(t) && (t.match(/\d/g) || []).length >= 8;

  function dedupeTokens(text) {
    if (!text) return text;
    const toks = pySplit(text), out = [];
    toks.forEach((t, i) => {
      const low = t.toLowerCase(), nxt = i + 1 < toks.length ? toks[i + 1].toLowerCase() : null;
      if (nxt !== null && low === nxt) return;
      if (nxt !== null && low.length <= 3 && ".-".includes(low[low.length - 1]) && nxt.startsWith(low) && nxt.length > low.length) return;
      const core = low.replace(/^\.+/, "");
      if (out.length && core.length <= 3 && core && out[out.length - 1].toLowerCase() !== low && out[out.length - 1].toLowerCase().endsWith("." + core)) return;
      out.push(t);
    });
    return out.join(" ");
  }

  function stripTrailingJunk(text, field) {
    if (!text) return text;
    const toks = pySplit(text);
    while (toks.length > 1) {
      const t = toks[toks.length - 1], f = fold(t);
      if (TRAILING_LABELS.has(f) || phoneLike(t) || (field === "ADDRESS" && AREA_CODE.test(t)) || (field === "SELLER" && /^\d{1,4}\.?$/.test(t))) { toks.pop(); continue; }
      break;
    }
    return toks.join(" ");
  }

  function cleanField(field, text) {
    if ((field !== "SELLER" && field !== "ADDRESS") || !text) return text;
    return stripTrailingJunk(dedupeTokens(text), field);
  }

  // ---------------------------------------------------------------- dùng trong FinBud
  /** Tách các dòng ML Kit ({text, bbox:[x0,y0,x1,y1]}) thành chữ, mỗi chữ lấy một phần khung theo độ dài
   *  (giống split_box_by_tokens của QuetHoaDon), theo thứ tự đọc. */
  function toWords(lines) {
    const words = [], boxes = [];
    const cy = b => (b[1] + b[3]) / 2;
    for (const l of lines.slice().sort((a, b) => cy(a.bbox) - cy(b.bbox) || a.bbox[0] - b.bbox[0])) {
      const toks = pySplit(l.text);
      if (!toks.length) continue;
      const [x0, y0, x1, y1] = l.bbox, total = toks.reduce((s, t) => s + t.length, 0) + toks.length - 1;
      let pos = 0;
      for (const t of toks) {
        const a = x0 + (x1 - x0) * pos / total, b = x0 + (x1 - x0) * (pos + t.length) / total;
        words.push(t);
        boxes.push([a, y0, b, y1]);
        pos += t.length + 1;
      }
    }
    return { words, boxes };
  }

  const R = {
    plain, fold, groupLines, parseAmounts, extractItems, checkItems, normalizeDate, extractTime, datetimeCandidates, readDatetime,
    normalizeAmount, mergeSplitAmount, parseAmountToken, amountCandidates, ruleTotal, dedupeTokens, stripTrailingJunk, cleanField, toWords,
  };
  if (typeof window !== "undefined") window.ReceiptRules = R;
  if (typeof module !== "undefined") module.exports = R;
})();
