/* FinBud – đọc số tiền từ câu nói/gõ tiếng Việt.
 * Hiểu: "ba mươi lăm nghìn", "một trăm hai mươi nghìn", "hai triệu rưỡi", "một triệu hai", "năm xị", "hai chục", "nửa triệu",
 *       "bốn lăm ca", "35k", "2 triệu rưỡi", "1,5 triệu", "45.000đ"...  Số nhỏ (<1000) không có đơn vị được hiểu là nghìn đồng.
 * Dùng:  parseAmt("ăn bún bò ba mươi lăm nghìn") -> { a: 35000, n: "ăn bún bò" } ;  a = 0 nếu không thấy số tiền. */
(function () {
  const D = { "một": 1, hai: 2, ba: 3, "bốn": 4, "năm": 5, "sáu": 6, "bảy": 7, "bẩy": 7, "tám": 8, "chín": 9 };
  const UNIT = { "nghìn": 1e3, "ngàn": 1e3, k: 1e3, ca: 1e3, ka: 1e3, "triệu": 1e6, tr: 1e6, "củ": 1e6, "tỷ": 1e9, "xị": 1e5 };
  const START = new Set([...Object.keys(D), "mười", "nửa"]);
  const CONT = new Set([...Object.keys(D), "mười", "nửa", "mươi", "trăm", "chục", "linh", "lẻ", "lăm", "nhăm", "mốt", "tư", "rưỡi", "rưởi", ...Object.keys(UNIT)]);
  const SKIP = new Set(["đồng", "đ", "vnd", "vnđ", "đ.", "[unk]"]);
  const isNum = t => /^\d+(?:[.,]\d+)?$/.test(t) || /^\d{1,3}(?:[.,]\d{3})+$/.test(t);
  const numVal = t => (/^\d{1,3}(?:[.,]\d{3})+$/.test(t) ? parseInt(t.replace(/[.,]/g, ""), 10) : parseFloat(t.replace(",", ".")));

  function runValue(tk) {
    let total = 0, cur = 0, last = 0, lastUnit = 0, big = 0, hasUnit = false;
    for (let i = 0; i < tk.length; i++) {
      const t = tk[i], prev = tk[i - 1];
      if (isNum(t)) { const v = numVal(t); cur += v; last = Number.isInteger(v) && v < 10 ? v : 0; }
      else if (t in D) { cur += D[t]; last = D[t]; }
      else if (t === "mười") { cur += 10; last = 0; }
      else if (t === "nửa") { cur = 0.5; last = 0; }
      else if (t === "mươi" || t === "chục") { cur += last * 9; if (!last) cur += 10 * (t === "chục" ? 1 : 0); last = 0; lastUnit = t === "chục" ? 10 : lastUnit; }
      else if (t === "trăm") { cur += last * 99; if (!last && !cur) cur = 100; last = 0; lastUnit = 100; }
      else if (t === "lăm" || t === "nhăm") { if (prev === "mười" || prev === "mươi" || prev === "chục") cur += 5; else if (last) { cur += last * 9 + 5; } else cur += 5; last = 0; }
      else if (t === "mốt") { if (prev === "mươi" || prev === "chục") cur += 1; else if (last) cur += last * 9 + 1; else cur += 1; last = 0; }
      else if (t === "tư") { cur += 4; last = 0; }
      else if (t === "linh" || t === "lẻ") { last = 0; }
      else if (t === "rưỡi" || t === "rưởi") { if (lastUnit) { if (lastUnit >= 1000) total += lastUnit / 2; else cur += lastUnit / 2; } }
      else if (t in UNIT) {
        const u = UNIT[t]; hasUnit = true;
        if (u === 1e5) { total += (cur || 1) * 1e5; cur = 0; lastUnit = 1e5; big = 1e5; }
        else { total += (cur || 1) * u; cur = 0; lastUnit = u; big = u; }
        last = 0;
      }
    }
    if (cur > 0) {                                  // phần lẻ sau đơn vị lớn: "một triệu hai" = 1,2 triệu
      if (big >= 1e3 && total > 0) total += cur < 10 ? cur * big / 10 : cur < 100 ? cur * big / 100 : cur * big / 1000;
      else total += cur;
    }
    const explicit = hasUnit || total >= 1000;                          // có đơn vị tiền rõ ràng hoặc số lớn
    if (!explicit && total > 0) total *= 1000;                          // "ba lăm", "hai chục", "15" -> nghìn đồng
    return { v: Math.round(total), explicit };
  }

  function parseAmt(text) {
    const raw = String(text).toLowerCase();
    // 1) số đi kèm "đ / vnd" (thông báo ngân hàng, gõ tay)
    let m = raw.match(/(\d{1,3}(?:[.,]\d{3})+|\d{4,9})\s*(?:vnd|vnđ|đ)(?![\p{L}])/u);
    if (m) { const a = parseInt(m[1].replace(/[.,]/g, ""), 10); return { a, n: raw.replace(m[0], " ").replace(/\s+/g, " ").trim(), explicit: true }; }
    // 2) tách "35k" -> "35 k", "1.5tr" -> "1.5 tr"
    const tk = raw.replace(/(\d)(k|tr)(?![\p{L}])/gu, "$1 $2").split(/\s+/).filter(Boolean);
    let best = null;
    for (let i = 0; i < tk.length; i++) {
      if (!(START.has(tk[i]) || isNum(tk[i]))) continue;
      let j = i + 1;
      while (j < tk.length && (CONT.has(tk[j]) || isNum(tk[j]) || SKIP.has(tk[j]))) j++;
      const run = tk.slice(i, j).filter(t => !SKIP.has(t)), { v, explicit } = runValue(run);
      if (v >= 1000 && v <= 5e8 && (!best || v >= best.v)) best = { v, i, j, explicit };
      i = j - 1;
    }
    if (!best) return { a: 0, n: raw.trim(), explicit: false };
    const note = tk.filter((t, k) => k < best.i || k >= best.j).filter(t => !SKIP.has(t)).join(" ");
    return { a: best.v, n: note || "chi tiêu", explicit: best.explicit };
  }
  if (typeof window !== "undefined") window.parseAmt = parseAmt;
  if (typeof module !== "undefined") module.exports = parseAmt;

  /* Từ vựng cho chế độ "giới hạn từ" của Vosk (tuỳ chọn). Từ không có trong model sẽ bị Vosk bỏ qua (xem logcat). */
  const VOICE_GRAMMAR = ("không một hai ba bốn năm sáu bảy tám chín mười mươi mốt tư lăm nhăm trăm linh lẻ nghìn ngàn triệu tỷ chục xị rưỡi nửa đồng k " +
    "ăn uống mua đi gửi đổ trả đóng nạp thuê photo in học bữa sáng trưa tối vặt bún bò phở cơm bánh mì trà sữa cà phê nước sinh tố kem chè lẩu nướng gà vịt heo cá tôm xôi cháo hủ tiếu quán nhà hàng đá đào " +
    "xe xăng buýt taxi grab vé tàu bãi sách vở bút tài liệu phí khóa giáo trình áo quần giày dép siêu thị tạp hóa cửa đồ mỹ phẩm điện thoại sạc tiền trọ điện internet thuốc khám").split(" ").concat(["[unk]"]);

  /* Vosk trả nhiều phương án nghe (alternatives): chọn phương án đầu tiên có số tiền hợp lệ, nếu không có thì lấy phương án tốt nhất. */
  function pickVoiceText(data) {
    const list = [data.text].concat(data.alternatives || []).filter(Boolean);
    return list.find(t => { const r = parseAmt(t); return r.a > 0 && r.explicit; }) || list.find(t => parseAmt(t).a > 0) || list[0] || "";
  }
  if (typeof window !== "undefined") { window.VOICE_GRAMMAR = VOICE_GRAMMAR; window.pickVoiceText = pickVoiceText; }
  if (typeof module !== "undefined") { module.exports.pickVoiceText = pickVoiceText; module.exports.VOICE_GRAMMAR = VOICE_GRAMMAR; }

})();
