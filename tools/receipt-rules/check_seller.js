// Kiểm tra chọn tên cửa hàng (receipt-engine.js: pickSeller) trên các kiểu đầu hóa đơn hay gặp.
// Chạy: node tools/receipt-rules/check_seller.js [đường dẫn receipt-engine.js khác để so]
const fs = require("fs"), path = require("path");
const root = path.join(__dirname, "..", "..");
global.fetch = undefined;
const enginePath = path.resolve(process.argv[2] || path.join(root, "www", "receipt-engine.js"));
const E = require(enginePath);
E.init(JSON.parse(fs.readFileSync(path.join(root, "www", "receipt_lexicon.json"), "utf8")),
       JSON.parse(fs.readFileSync(path.join(root, "www", "receipt_model.json"), "utf8")));

const L = (text, y, x = 20, w = 360) => ({ text, left: x, top: y, right: x + w, bottom: y + 22 });
const body = y => [L("Tên hàng SL Đơn giá Thành tiền", y), L("Sữa tươi 2 36,500 73,000", y + 30), L("Bánh mì 1 15,000", y + 60),
  L("Tổng cộng: 88,000", y + 100), L("Tiền khách đưa 100,000", y + 130), L("Tiền thừa 12,000", y + 160), L("Cảm ơn quý khách", y + 190)];
const make = head => { let y = 20; const h = head.map(t => { const l = L(t, y); y += 30; return l; }); return [...h, ...body(y + 20)]; };

// [đầu hóa đơn, tên cửa hàng mong đợi (so khớp không phân biệt hoa thường, chỉ cần chứa)]
const CASES = [
  [["CO.OP FOOD 0945984733", "123 Lê Lợi, Q.1", "HÓA ĐƠN BÁN LẺ", "Ngày: 20/09/2019 14:35"], "co.op food"],
  [["HÓA ĐƠN BÁN LẺ", "SIÊU THỊ VINMART", "72 Lê Thánh Tôn, Q1"], "vinmart"],
  [["PHIẾU THANH TOÁN", "Highlands Coffee", "Tel: 028 3822 1234"], "highlands"],
  [["PHIẾU TÍNH TIỀN", "Bàn 5", "Quán Phở Thìn", "13 Lò Đúc"], "phở thìn"],
  [["BÁCH HÓA XANH", "HÓA ĐƠN GTGT", "Số HĐ: 0012345"], "bách hóa xanh"],
  [["Nhà thuốc Long Châu", "ĐC: 379 Hai Bà Trưng", "HÓA ĐƠN BÁN HÀNG"], "long châu"],
  [["Quầy thuốc Minh Châu", "Thu ngân: Lan", "Ngày 05/11/2020"], "quầy thuốc minh châu"],
  [["CIRCLE K", "Store 1023", "BILL"], "circle k"],
  [["HÓA ĐƠN", "Cửa hàng tiện lợi GS25", "MST: 0312345678"], "gs25"],
  [["Thu ngân: Minh", "Ngày: 01/02/2021", "TRÀ SỮA TOCOTOCO"], "tocotoco"],
  [["Công ty TNHH Lotteria Việt Nam", "Chi nhánh Q.3", "PHIẾU THANH TOÁN"], "lotteria"],
  [["HÓA ĐƠN BÁN LẺ", "Liên 1", "Ngày 05/11/2020"], ""],                        // không có tên: để trống còn hơn ghi tiêu đề
];

let ok = 0;
for (const [head, want] of CASES) {
  const r = E.extract(make(head), 400, 500);
  const got = (r.seller || "").toLowerCase(), pass = want ? got.includes(want) : got === "";
  if (pass) ok++;
  console.log(`${pass ? "ĐÚNG" : "SAI "}  ${JSON.stringify(r.seller || "")}${pass ? "" : "   (mong đợi: " + (want || "để trống") + ")"}`);
}
console.log(`\n${ok}/${CASES.length} mẫu chọn đúng tên cửa hàng`);

// Dòng nào là tiêu đề / nhãn (bỏ qua) và tên nào là tên cửa hàng thật (phải giữ, kể cả tên bắt đầu giống nhãn)
const LABELS = { "HÓA ĐƠN BÁN LẺ": 1, "Phiếu tính tiền": 1, "Bàn 5": 1, "Bàn 12": 1, "Quầy 02": 1, "Liên 2": 1, "ĐC: 379 Hai Bà Trưng": 1,
  "ĐT: 0283822": 1, "Thu ngân: Lan": 1, "KH: Nguyễn": 1, "Ngày 05/11": 1, "Website: abc.vn": 1,
  "Quầy thuốc ABC": 0, "Khánh Linh Mart": 0, "Tổng công ty Bưu điện": 0, "DC Mart": 0, "Web Coffee": 0, "Kho Mart": 0,
  "Bánh mì Huỳnh Hoa": 0, "Phúc Long": 0, "Gà rán KFC": 0 };
let lok = 0;
for (const [s, want] of Object.entries(LABELS)) {
  const got = E._t.notSeller(s) ? 1 : 0;
  if (got === want) lok++;
  else console.log(`SAI   ${JSON.stringify(s)} ${got ? "bị bỏ qua" : "được giữ"}`);
}
console.log(`${lok}/${Object.keys(LABELS).length} dòng phân biệt đúng tên / nhãn`);
process.exitCode = ok === CASES.length && lok === Object.keys(LABELS).length ? 0 : 1;
