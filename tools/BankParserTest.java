package com.tnn.finbud;

public class BankParserTest {
    static int ok = 0, bad = 0;
    static void t(String name, String text, String exp) {
        BankParser.Result r = BankParser.parse(text);
        String got = BankParser.isSensitive(text) ? "SENSITIVE" : r == null ? "null" : (r.outgoing ? "OUT " : "IN  ") + r.amount + " | " + r.note;
        boolean pass = got.equals(exp);
        if (pass) ok++; else bad++;
        System.out.println((pass ? "OK   " : "SAI  ") + name + (pass ? "" : "\n       nhận được: " + got + "\n       mong đợi : " + exp));
    }
    static void d(String name, boolean cond) {
        if (cond) ok++; else bad++;
        System.out.println((cond ? "OK   " : "SAI  ") + name);
    }
    public static void main(String[] a) {
        t("MB (+số dư)", "TK 0123456789 -45,000VND luc 10:21 05/11/2026. So du: 1,234,567VND. ND: Quet QR Highland", "OUT 45000 | Quet QR Highland");
        t("VCB, ngày có dấu gạch", "Số dư TK VCB 0011001234567 -45,000 VND lúc 05-11-2026 10:21:33. Số dư 1,234,567 VND. Ref MBVCB.123456. ND: thanh toan hoa don", "OUT 45000 | thanh toan hoa don");
        t("Techcombank", "TK 19031234567 GD: -45,000 VND 05/11/26 10:21 ND: Thanh toan tien an", "OUT 45000 | Thanh toan tien an");
        t("MoMo thanh toán", "Bạn đã thanh toán thành công 45.000đ cho Highlands Coffee. Mã giao dịch 123456789", "OUT 45000 | Highlands Coffee");
        t("ZaloPay", "Thanh toán thành công 35.000đ tại Cửa hàng Phúc Long", "OUT 35000 | Cửa hàng Phúc Long");
        t("Chuyển tiền đi", "Bạn đã chuyển tiền thành công 200.000đ đến Nguyen Van A", "OUT 200000 | Nguyen Van A");
        t("Tiền vào (bỏ qua ở service)", "TK 0123456789 +500,000 VND ND: Nhan tien luong", "IN  500000 | Nhan tien luong");
        t("Nhận tiền MoMo", "Bạn đã nhận 50.000đ từ NGUYEN VAN B", "IN  50000 | ");
        t("Dấu trừ Unicode", "TK 0123456789 \u221245,000 VND ND: tra sua", "OUT 45000 | tra sua");
        t("Không có dấu phân cách", "TK 0123456789 -45000VND ND: gui xe", "OUT 45000 | gui xe");
        t("Hai khoản: lấy khoản đầu", "TK 0123 -45,000 VND. Phi -1,100 VND. ND: chuyen khoan", "OUT 45000 | chuyen khoan");
        t("Số tài khoản không bị nhầm", "TK 0123456789 -120.000 VND ND: mua sach", "OUT 120000 | mua sach");
        t("OTP phải bị loại", "Ma OTP cua ban la 123456. Khong chia se cho bat ky ai", "SENSITIVE");
        t("Mã xác thực", "Mã xác thực giao dịch 45.000đ là 998877", "SENSITIVE");
        t("Chỉ báo số dư", "Số dư TK 0123456789 là 1,000,000 VND", "null");
        t("Không có số tiền", "Giao dịch ngày 05-11-2026 đã thực hiện", "null");
        t("Không rõ chiều", "Thông báo: 45.000đ giao dịch", "null");
        t("Ngày kiểu 11-2026 không là tiền", "GD luc 05-11-2026 thanh toan", "null");
        t("Số nhỏ bị loại", "TK 0123 -500 VND ND: phi", "null");
        t("Ghi chú có số tài khoản dài", "TK 0123 -45,000 VND ND: Chuyen khoan toi 0987654321 tien com", "OUT 45000 | Chuyen khoan toi tien com");
        t("Verification code (EN)", "Your verification code is 482913 for transaction -45,000 VND", "SENSITIVE");
        t("Smart OTP", "Smart OTP: nhap ma de xac nhan giao dich 45.000đ", "SENSITIVE");
        t("One-time password", "One-time password 998877 valid for 5 minutes", "SENSITIVE");
        String s1 = BankParser.signature("TK 0123 -45,000 VND luc 10:21 ND: tra sua");
        String s2 = BankParser.signature("TK 0123   -45,000 VND luc 10:21 ND: tra sua ");
        String s3 = BankParser.signature("TK 0123 -45,000 VND luc 10:22 ND: tra sua");
        d("chữ ký bỏ qua khoảng trắng thừa", s1.equals(s2));
        d("chữ ký khác khi nội dung khác", !s1.equals(s3));
        d("chữ ký dài 12 ký tự", s1.length() == 12);
        long W = 120000L, T = 1_000_000_000L;
        String e = "com.mbmobile|45000|" + s1 + "|" + T;
        d("trùng: đúng thông báo cũ đẩy lại sau 1 giờ", BankParser.isDuplicate(e, "com.mbmobile", 45000, s1, T + 3600000L, W));
        d("trùng: khác câu chữ nhưng cùng số tiền sau 60 giây", BankParser.isDuplicate(e, "com.mbmobile", 45000, s3, T + 60000L, W));
        d("KHÔNG trùng: khác câu chữ, cách 5 phút (hai lần mua thật)", !BankParser.isDuplicate(e, "com.mbmobile", 45000, s3, T + 300000L, W));
        d("KHÔNG trùng: khác số tiền", !BankParser.isDuplicate(e, "com.mbmobile", 50000, s3, T + 1000L, W));
        d("KHÔNG trùng: khác ngân hàng", !BankParser.isDuplicate(e, "com.VCB", 45000, s1, T + 1000L, W));
        d("entry hỏng không gây lỗi", !BankParser.isDuplicate("rac", "com.mbmobile", 45000, s1, T, W));
        System.out.println("mask:  " + BankParser.mask("TK 0123456789 -45,000VND ND: Quet QR"));
        System.out.println("\nĐúng " + ok + "/" + (ok + bad));
        if (bad > 0) System.exit(1);
    }
}
