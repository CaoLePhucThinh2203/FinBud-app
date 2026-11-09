/* FinBud – tự ghi chi tiêu từ thông báo biến động số dư (chỉ chạy trong app Android).
 * Phụ thuộc các biến/hàm có sẵn trong index.html: S, save, render, toast, esc, ds, classify, $, (tuỳ chọn) ReceiptEngine.
 * Plugin native: BankNotifier (BankNotifierPlugin.java). Dịch vụ nghe thông báo chạy nền, JS chỉ lấy giao dịch đã phân tích. */
const BN = window.Capacitor && Capacitor.Plugins && Capacitor.Plugins.BankNotifier;

/* Lấy các giao dịch đang chờ từ native, ghi vào lịch sử, rồi báo native xóa khỏi hàng đợi. */
async function syncBank() {
  if (!BN || !S.set || !S.set.bankConsent) return;
  try {
    const r = await BN.getPending();
    const items = (r && r.items) || [];
    if (!items.length) return;
    if (window.ReceiptEngine) await ReceiptEngine.ready;
    let added = 0;
    const ids = [];
    for (const it of items) {
      ids.push(it.id);
      if (S.tx.some(t => t.nid === it.id)) continue;               // đã ghi rồi (chống trùng)
      const cat = window.ReceiptEngine && ReceiptEngine._t ? ReceiptEngine._t.categorize(it.note) : classify(it.note);
      S.tx.unshift({ id: Date.now() + added, nid: it.id, a: it.amount, c: cat, n: it.note, s: "Thông báo", d: ds(new Date(it.ts)) });
      added++;
    }
    if (added) { save(); render(); toast("⚡ Đã ghi " + added + " giao dịch từ thông báo"); }
    await BN.ack({ ids });
  } catch (e) { console.warn("syncBank", e); }
}

/* Điền khung "Thông báo ngân hàng" trong màn Cài đặt (gọi sau khi render() vẽ #bn). */
async function bankUI() {
  const el = $("#bn");
  if (!el) return;
  if (!BN) { el.innerHTML = '<div class="mut">Tính năng này chỉ có trong app Android.</div>'; return; }
  let st;
  try { st = await BN.getStatus(); } catch (e) { el.innerHTML = '<div class="mut">Không đọc được trạng thái.</div>'; return; }
  const on = !!(st.enabled && S.set.bankConsent);
  el.innerHTML =
    '<div style="font-size:14px">' + (on ? "✅ Đang bật" : "⚪ Đang tắt") + "</div>" +
    '<div class="mut" style="margin:6px 0">Chỉ đọc thông báo của các ngân hàng/ví bạn chọn. Bỏ qua mọi thông báo có mã OTP. Chỉ giữ số tiền, nội dung ngắn và thời gian; xử lý hoàn toàn trên máy.</div>' +
    '<button class="btn ' + (on ? "g" : "") + '" style="width:100%" onclick="bankEnable()">' + (on ? "Mở cài đặt quyền thông báo" : "Bật tự động ghi từ thông báo") + "</button>" +
    (!on ? '<div class="mut" style="margin-top:8px">Nếu công tắc quyền bị mờ (Android 13 trở lên, app cài thủ công): mở "Thông tin ứng dụng" → menu ⋮ → "Cho phép cài đặt bị hạn chế", rồi quay lại bật quyền.</div>' +
      '<button class="chip" style="margin-top:6px" onclick="BN.openAppSettings()">Mở thông tin ứng dụng</button>' : "") +
    (on ? '<div class="mut" style="margin-top:10px">Chọn ngân hàng/ví được phép:</div>' +
      st.banks.map(b => '<label class="s">' + esc(b.label) + '<input type="checkbox" style="width:auto" ' + (b.enabled ? "checked" : "") +
        ' onchange="BN.setBankEnabled({pkg:\'' + b.pkg + '\',enabled:this.checked})"></label>').join("") : "") +
    (st.samples ? '<button class="chip" style="margin-top:8px" onclick="copySamples()">📋 Sao chép ' + st.samples + " mẫu thông báo chưa đọc được (đã che số)</button>" : "");
}

async function bankEnable() {
  if (!S.set.bankConsent) {
    const ok = confirm("FinBud sẽ đọc thông báo biến động số dư của các ngân hàng/ví bạn chọn để tự ghi chi tiêu.\n\n" +
      "• Bỏ qua mọi thông báo chứa mã OTP/mật khẩu.\n• Chỉ lưu số tiền, nội dung ngắn và thời gian; không lưu số tài khoản hay số dư.\n" +
      "• Xử lý hoàn toàn trên máy, không gửi đi đâu.\n• Bạn có thể tắt bất cứ lúc nào trong cài đặt Android.\n\nBạn đồng ý?");
    if (!ok) return;
    S.set.bankConsent = true; save();
  }
  try { await BN.openSettings(); } catch (e) { toast("Không mở được cài đặt"); }
}

async function copySamples() {
  try {
    const r = await BN.getSamples();
    await navigator.clipboard.writeText((r.items || []).join("\n"));
    toast("Đã sao chép mẫu (đã che số)");
  } catch (e) { toast("Không sao chép được"); }
}

window.addEventListener("load", () => {
  syncBank();
  setInterval(() => { if (!document.hidden) syncBank(); }, 5000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) { syncBank(); if (S.tab === "set") bankUI(); } });
});
