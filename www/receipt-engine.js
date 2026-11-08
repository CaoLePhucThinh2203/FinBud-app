/* FinBud – bộ trích xuất hóa đơn chạy trên máy (offline).
 * Đầu vào: các dòng chữ do ML Kit trả về. Đầu ra: tổng tiền, ngày, người bán, danh mục.
 * Logic phải GIỐNG HỆT notebook (cell "LÕI DÙNG CHUNG"). Sửa một bên thì sửa cả hai bên.
 * Cần file receipt_lexicon.json (bắt buộc) và receipt_model.json (tuỳ chọn, xuất từ notebook; thiếu thì chạy bằng luật). */
(function () {
  const FEATURES = ["y_rel","x_rel","w_rel","h_rel","idx_rel","kw_total","kw_sub","kw_cash","kw_change","kw_disc","kw_vat",
    "n_amt","amt_log","digit_ratio","alpha_ratio","upper_ratio","len_rel","has_date","has_time","has_phone","kw_addr","kw_seller"];
  let LEX = null, MODEL = null;
  const fold = s => String(s).toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/đ/g, "d").replace(/\s+/g, " ").trim();
  const normTok = s => fold(s).replace(/[^a-z0-9]+/g, " ").trim();
  const hasKw = (t, ws) => { const p = " " + t + " "; return ws.some(w => p.includes(" " + w + " ")) ? 1 : 0; };
  const AMT = /(?<![\d.,])(\d{1,3}(?:[.,]\d{3})+|\d{4,9})(?!\d)/g;
  const DATE = /(?<!\d)(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4}|\d{2})(?!\d)/g;
  const TIME = /(?<!\d)(\d{1,2}):(\d{2})(?!\d)/;
  const PHONE = /(?<!\d)(?:\+84|0)\d[\d .\-]{7,11}\d(?!\d)/;
  const fixTok = t => (/^[0-9OoIl.,]{4,}$/.test(t) && (t.match(/\d/g) || []).length >= 2 ? t.replace(/[Oo]/g, "0").replace(/[Il]/g, "1") : t);
  function amounts(text) {
    const txt = String(text).replace(DATE, " ").split(/\s+/).filter(Boolean).map(fixTok).join(" "), out = [];
    for (const m of txt.matchAll(AMT)) { const v = parseInt(m[1].replace(/[.,]/g, ""), 10); if (v >= 1000 && v <= 500000000) out.push(v); }
    return out;
  }
  function findDate(text) {
    for (const m of String(text).matchAll(DATE)) {
      const d = +m[1], mo = +m[2]; let y = +m[3]; if (y < 100) y += 2000;
      if (mo >= 1 && mo <= 12 && y >= 2015 && y <= 2035 && d >= 1 && d <= new Date(y, mo, 0).getDate()) return y + "-" + String(mo).padStart(2, "0") + "-" + String(d).padStart(2, "0");
    }
    return "";
  }
  function mergeRows(items) {
    items = items.filter(it => String(it.text || "").trim());
    if (!items.length) return [];
    const hs = items.map(it => it.bbox[3] - it.bbox[1]).sort((a, b) => a - b), thr = Math.max(4, 0.5 * hs[Math.floor(hs.length / 2)]);
    const cy = b => (b[1] + b[3]) / 2, rows = [];
    for (const it of items.slice().sort((a, b) => cy(a.bbox) - cy(b.bbox) || a.bbox[0] - b.bbox[0])) {
      const r = rows[rows.length - 1];
      if (r && Math.abs(cy(it.bbox) - r.cy) <= thr) { r.items.push(it); r.cy = r.items.reduce((s, x) => s + cy(x.bbox), 0) / r.items.length; }
      else rows.push({ cy: cy(it.bbox), items: [it] });
    }
    return rows.map(r => { const its = r.items.slice().sort((a, b) => a.bbox[0] - b.bbox[0]);
      return { text: its.map(x => String(x.text).trim()).join(" "), bbox: [Math.min(...its.map(x => x.bbox[0])), Math.min(...its.map(x => x.bbox[1])), Math.max(...its.map(x => x.bbox[2])), Math.max(...its.map(x => x.bbox[3]))] }; });
  }
  function rowFeatures(row, W, H, idx, n) {
    const raw = row.text, t = normTok(raw), [x0, y0, x1, y1] = row.bbox, chars = [...raw], L = Math.max(1, chars.length), k = LEX.kw;
    const al = chars.filter(c => /\p{L}/u.test(c)).length, am = amounts(raw);
    return [(y0 + y1) / 2 / H, x0 / W, (x1 - x0) / W, (y1 - y0) / H, idx / Math.max(1, n - 1),
      hasKw(t, k.total), hasKw(t, k.sub), hasKw(t, k.cash), hasKw(t, k.change), hasKw(t, k.disc), hasKw(t, k.vat),
      Math.min(am.length, 3) / 3, am.length ? Math.log10(Math.max(...am) + 1) / 9 : 0,
      chars.filter(c => /[0-9]/.test(c)).length / L, al / L, chars.filter(c => /\p{Lu}/u.test(c)).length / Math.max(1, al), Math.min(L, 60) / 60,
      findDate(raw) ? 1 : 0, TIME.test(raw) ? 1 : 0, PHONE.test(raw) ? 1 : 0, hasKw(t, k.addr), hasKw(t, k.seller)];
  }
  const heurTotal = F => 2 * F.kw_total - F.kw_sub - 2.5 * F.kw_cash - 2.5 * F.kw_change - 2 * F.kw_disc - F.kw_vat * (1 - F.kw_total) + 0.8 * (F.y_rel > 0.45) + 0.4 * (F.n_amt > 0) - F.has_date;
  const heurSeller = F => F.upper_ratio + 0.5 * F.alpha_ratio - 1.5 * F.has_phone - 1.5 * F.kw_addr - F.has_date - (F.n_amt > 0) + F.kw_seller + 0.5 * (1 - F.idx_rel) - 3 * (F.y_rel > 0.3);
  const lin = (lab, f) => { const m = MODEL.kie[lab]; return m.b + m.w.reduce((s, w, i) => s + w * f[i], 0); };
  function extractRows(rows, W, H) {
    const n = rows.length, feats = rows.map((r, i) => rowFeatures(r, W, H, i, n)), Fs = feats.map(f => Object.fromEntries(FEATURES.map((k, i) => [k, f[i]])));
    const ok = !!(MODEL && MODEL.features && MODEL.features.join() === FEATURES.join()), useT = ok && !!MODEL.kie.TOTAL_COST, useS = ok && !!MODEL.kie.SELLER;
    const ts = feats.map((f, i) => useT ? lin("TOTAL_COST", f) : heurTotal(Fs[i])), cand = new Map();
    rows.forEach((r, i) => { for (const a of new Set(amounts(r.text))) { const c = cand.get(a) || { s: -1e9, n: 0 }; c.s = Math.max(c.s, ts[i]); c.n++; cand.set(a, c); } });
    const mx = r => Math.max(0, ...amounts(r.text));
    const cash = rows.filter((r, i) => Fs[i].kw_cash && !Fs[i].kw_change).map(mx), chg = rows.filter((r, i) => Fs[i].kw_change).map(mx);
    for (const [a, c] of cand) c.s += 0.35 * Math.min(c.n - 1, 3) + 0.15 * Math.log10(a);
    if (cash.length && chg.length) { const t = Math.max(...cash) - Math.max(...chg); if (cand.has(t)) cand.get(t).s += 1; }
    const ranked = [...cand.entries()].sort((a, b) => b[1].s - a[1].s);
    let total = 0, conf = 0;
    if (ranked.length) { total = ranked[0][0]; const b = ranked[0][1].s, gap = ranked.length > 1 ? b - ranked[1][1].s : 1; conf = (1 / (1 + Math.exp(-b))) * (0.5 + 0.5 * Math.min(1, gap)); }
    let date = "", time = "";
    for (const r of rows) { const d = findDate(r.text); if (d) { date = d; const m = r.text.match(TIME); time = m ? m[0] : ""; break; } }
    let seller = "", best = -1e9; const th = useS ? -1 : 0.3;
    feats.forEach((f, i) => { if (Fs[i].y_rel > 0.3) return; const s = useS ? lin("SELLER", f) : heurSeller(Fs[i]); if (s > best && s > th) { best = s; seller = rows[i].text; } });
    return { total, candidates: ranked.slice(0, 3).map(x => x[0]), conf: Math.round(conf * 1000) / 1000, date, time, seller: seller.trim() };
  }
  function categorize(text) {
    const t = normTok(text);
    if (MODEL && MODEL.cat) {
      const c = MODEL.cat, p = new Map();
      for (const w0 of t.split(" ").filter(Boolean)) {
        const w = " " + w0 + " ";
        for (const n of [2, 3, 4]) { let off = 0; const add = g => p.set(g, (p.get(g) || 0) + 1); add(w.slice(0, n)); while (off + n < w.length) { off++; add(w.slice(off, off + n)); } if (off === 0) break; }
      }
      const x = new Map(); for (const [g, v] of p) if (g in c.vocab) x.set(c.vocab[g], (1 + Math.log(v)) * c.idf[c.vocab[g]]);
      const nrm = Math.sqrt([...x.values()].reduce((s, v) => s + v * v, 0)) || 1;
      const z = c.intercept.map((b, k) => { let s = b; for (const [j, v] of x) s += c.coef[k][j] * v / nrm; return s; });
      const mz = Math.max(...z), e = z.map(v => Math.exp(v - mz)), sm = e.reduce((a, b) => a + b, 0), pr = e.map(v => v / sm), k = pr.indexOf(Math.max(...pr));
      if (pr[k] >= 0.45) return c.classes[k];
    }
    const pad = " " + t + " ", sc = {}; for (const k of LEX.categories) sc[k] = 0;
    for (const [cat, kws] of Object.entries(LEX.cat_kw)) for (const [kw, w] of kws) if (pad.includes(" " + kw + " ")) sc[cat] += w;
    const best = Object.keys(sc).reduce((a, b) => (sc[b] > sc[a] ? b : a));
    return sc[best] > 0 ? best : "other";
  }
  const E = {
    ready: null, mode: () => (MODEL && MODEL.kie ? "model" : "rules"),
    init(lex, model) { LEX = lex; MODEL = model || null; },
    /* lines: [{text,left,top,right,bottom}] từ plugin ReceiptScanner */
    extract(lines, W, H) {
      if (!LEX) return null;
      const rows = mergeRows(lines.map(l => ({ text: l.text, bbox: [l.left, l.top, l.right, l.bottom] })));
      if (!rows.length) return null;
      const o = extractRows(rows, W, H);
      o.category = categorize(o.seller + " " + rows.slice(0, 60).map(r => r.text).join(" ")); o.mode = E.mode();
      return o;
    },
    _t: { mergeRows, extractRows, categorize, amounts, findDate },
  };
  const get = u => fetch(u).then(r => (r.ok ? r.json() : null)).catch(() => null);
  E.ready = typeof fetch === "function" ? Promise.all([get("receipt_lexicon.json"), get("receipt_model.json")]).then(([l, m]) => { if (l) E.init(l, m); return E; }) : Promise.resolve(E);
  if (typeof window !== "undefined") window.ReceiptEngine = E;
  if (typeof module !== "undefined") module.exports = E;
})();
