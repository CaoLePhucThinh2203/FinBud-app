"""Từ vựng gốc để sinh dữ liệu train cho bộ phân loại danh mục chi tiêu.

Mỗi danh mục gồm:
  items   – món/khoản chi (viết tự nhiên, có dấu)
  brands  – tên cửa hàng/dịch vụ hay gặp trên hóa đơn, thông báo ngân hàng
  verbs   – động từ/cụm mở đầu đặc trưng của danh mục
Muốn app nhận thêm từ nào thì thêm vào đây rồi chạy lại train.py.
"""

CATEGORIES = ["food", "move", "study", "shop", "health", "fun", "other"]

SEED = {
    "food": {
        "verbs": ["ăn", "uống", "đi ăn", "ăn sáng", "ăn trưa", "ăn tối", "ăn khuya", "gọi", "đặt", "mua", "đi chợ mua", "trả tiền"],
        "items": [
            "phở bò", "phở gà", "bún bò", "bún bò huế", "bún chả", "bún riêu", "bún đậu mắm tôm", "bún thịt nướng", "hủ tiếu",
            "mì quảng", "cao lầu", "cơm tấm", "cơm gà", "cơm sườn", "cơm văn phòng", "cơm bình dân", "cơm rang", "cơm chiên",
            "bánh mì", "bánh mì pate", "bánh mì chảo", "xôi", "xôi gà", "xôi xéo", "cháo", "cháo lòng", "bánh cuốn", "bánh xèo",
            "bánh canh", "bánh bao", "bánh giò", "gỏi cuốn", "lẩu", "lẩu thái", "lẩu bò", "đồ nướng", "ốc", "hải sản", "gà rán",
            "khoai tây chiên", "pizza", "hamburger", "mì cay", "mì tôm", "miến", "trà sữa", "trà đào", "trà chanh", "trà tắc",
            "cà phê", "cà phê sữa đá", "cafe", "bạc xỉu", "sinh tố", "nước mía", "nước ép", "nước suối", "nước ngọt", "coca",
            "pepsi", "sting", "bò húc", "bia", "kem", "chè", "bánh tráng trộn", "bánh flan", "hoa quả", "trái cây", "sữa chua",
            "sữa tươi", "bim bim", "snack", "đồ ăn vặt", "bánh ngọt", "bánh kem", "đồ ăn", "cơm hộp", "bữa sáng", "bữa trưa",
            "bữa tối", "đồ uống", "rau", "thịt", "thịt heo", "thịt bò", "cá", "trứng", "gạo", "mắm muối", "dầu ăn", "đồ nhậu",
            "đi nhậu", "kimbap", "tokbokki", "sushi", "dimsum", "bánh tráng nướng", "nem chua", "bột chiên", "há cảo",
        ],
        "brands": [
            "highlands", "phúc long", "starbucks", "the coffee house", "katinat", "phê la", "trung nguyên", "cộng cà phê",
            "kfc", "lotteria", "jollibee", "mcdonald", "popeyes", "texas chicken", "pizza hut", "domino", "burger king",
            "gong cha", "tocotoco", "koi thé", "mixue", "grabfood", "shopeefood", "baemin", "phở 24", "bánh mì huỳnh hoa",
        ],
    },
    "move": {
        "verbs": ["đổ", "đi", "gọi", "đặt", "trả", "mua", "gửi", "sửa", "thay", "bơm", "rửa"],
        "items": [
            "xăng", "đổ xăng", "đổ xăng xe máy", "dầu diesel", "gửi xe", "giữ xe", "vé gửi xe", "vé xe tháng", "phí gửi xe",
            "xe buýt", "vé xe buýt", "vé bus", "grab bike", "grab car", "xe ôm", "xe ôm công nghệ", "taxi", "vé tàu", "vé tàu hỏa",
            "vé máy bay", "vé xe khách", "xe khách", "xe limousine", "metro", "vé metro", "phí cầu đường", "phí cao tốc",
            "phí bot", "sửa xe", "sửa xe máy", "thay nhớt", "vá xe", "vá lốp", "bơm xe", "rửa xe", "thay lốp", "thay săm",
            "bảo dưỡng xe", "đăng kiểm", "thuê xe máy", "thuê xe", "phí đỗ xe", "đỗ ô tô", "sạc xe điện", "thay ắc quy",
            "bảo hiểm xe máy", "tiền xe", "tiền xăng", "cuốc xe", "đi xe", "đi về quê",
        ],
        "brands": [
            "grab", "be", "xanh sm", "gojek", "mai linh", "vinasun", "petrolimex", "pvoil", "phương trang", "futa",
            "vietjet", "vietnam airlines", "bamboo airways", "vetc", "epass", "vinfast", "honda head",
        ],
    },
    "study": {
        "verbs": ["mua", "đóng", "in", "photo", "đăng ký", "nộp", "trả"],
        "items": [
            "bút bi", "bút chì", "bút mực", "bút máy", "bút dạ quang", "bút highlight", "bút xóa", "ruột bút", "thước kẻ",
            "compa", "êke", "tẩy", "gôm", "vở", "vở ô ly", "vở kẻ ngang", "sổ tay", "giấy a4", "giấy kiểm tra", "giấy note",
            "sách", "sách giáo khoa", "sách tham khảo", "giáo trình", "từ điển", "máy tính casio", "máy tính cầm tay",
            "hộp bút", "kẹp giấy", "bìa hồ sơ", "file kẹp", "mực in", "photo tài liệu", "in tài liệu", "photocopy", "in màu",
            "đóng gáy", "in bài tập", "in đề cương", "tài liệu ôn thi", "học phí", "tiền học", "học thêm", "gia sư",
            "khóa học", "khóa học online", "khóa ielts", "khóa toeic", "lệ phí thi", "phí thi", "đăng ký thi", "thi lại",
            "học lại", "tín chỉ", "thẻ thư viện", "văn phòng phẩm", "bảng vẽ", "màu vẽ", "giấy vẽ", "dụng cụ học tập",
            "tiền tài liệu", "đồ án", "in luận văn", "đóng quyển báo cáo",
        ],
        "brands": [
            "fahasa", "nhà sách phương nam", "nhà sách", "thiên long", "hồng hà", "udemy", "coursera", "duolingo",
            "elsa speak", "prep", "ielts fighter", "anh ngữ", "trung tâm tiếng anh", "tiệm photo",
        ],
    },
    "shop": {
        "verbs": ["mua", "đặt", "order", "săn", "sắm"],
        "items": [
            "áo", "áo thun", "áo sơ mi", "áo khoác", "áo hoodie", "quần", "quần jean", "quần short", "váy", "đầm", "giày",
            "giày thể thao", "dép", "dép lê", "túi xách", "ví", "balo", "mũ", "nón", "kính mát", "đồng hồ", "tai nghe",
            "sạc điện thoại", "cáp sạc", "ốp lưng", "cường lực", "chuột máy tính", "bàn phím", "điện thoại", "laptop",
            "loa bluetooth", "son", "mỹ phẩm", "sữa rửa mặt", "kem chống nắng", "toner", "serum", "dầu gội", "sữa tắm",
            "kem đánh răng", "bàn chải", "giấy vệ sinh", "khăn giấy", "nước giặt", "nước rửa chén", "nồi", "chảo", "chăn",
            "gối", "ga giường", "móc quần áo", "đồ gia dụng", "đồ dùng cá nhân", "quà vặt siêu thị", "đồ online",
            "phụ kiện", "trang sức", "nhẫn", "vòng tay", "tất", "đồ lót", "quạt mini", "đèn bàn", "bình nước", "ô dù",
        ],
        "brands": [
            "siêu thị", "winmart", "vinmart", "bách hóa xanh", "circle k", "gs25", "ministop", "family mart", "7 eleven",
            "coopmart", "co op food", "aeon", "lotte mart", "big c", "go", "mega market", "emart", "shopee", "lazada",
            "tiki", "tiktok shop", "uniqlo", "zara", "h&m", "thế giới di động", "điện máy xanh", "fpt shop", "cellphones",
            "guardian", "watsons", "hasaki", "cocoon", "miniso", "daiso",
        ],
    },
    "health": {
        "verbs": ["mua", "khám", "uống", "tiêm", "chữa", "đi khám", "lấy"],
        "items": [
            "thuốc", "thuốc cảm", "thuốc ho", "thuốc đau đầu", "thuốc hạ sốt", "thuốc dạ dày", "thuốc đau bụng",
            "thuốc tiêu chảy", "thuốc dị ứng", "thuốc kháng sinh", "kháng sinh", "thuốc nhỏ mắt", "thuốc bôi",
            "thuốc giảm đau", "paracetamol", "panadol", "efferalgan", "berberin", "vitamin", "vitamin c", "kẽm",
            "thực phẩm chức năng", "khẩu trang", "băng cá nhân", "bông băng", "nước muối sinh lý", "dầu gió", "cao dán",
            "salonpas", "nhiệt kế", "que thử", "khám bệnh", "khám răng", "nha khoa", "lấy cao răng", "trám răng",
            "nhổ răng", "niềng răng", "khám mắt", "đo mắt", "khám da liễu", "bệnh viện", "phòng khám", "xét nghiệm",
            "xét nghiệm máu", "chụp x quang", "siêu âm", "tiêm phòng", "tiêm vắc xin", "vắc xin", "bảo hiểm y tế",
            "viện phí", "tiền thuốc", "vật lý trị liệu", "châm cứu", "khám tổng quát", "khám sức khỏe", "đơn thuốc",
            "men vi sinh", "siro ho", "thuốc say xe",
        ],
        "brands": [
            "nhà thuốc", "hiệu thuốc", "pharmacity", "long châu", "an khang", "medicare", "bệnh viện bạch mai",
            "bệnh viện chợ rẫy", "vinmec", "hoàn mỹ", "medlatec", "nha khoa kim", "phòng khám đa khoa",
        ],
    },
    # Tiền nhà/điện/nước/mạng thường đã nằm trong "Chi phí cố định", nên không có danh mục riêng:
    # các từ này được gộp vào "other" (xem cuối file) để app vẫn nhận ra chứ không đoán bừa.
    "_fixed": {
        "verbs": ["đóng", "trả", "nạp", "thanh toán", "gia hạn"],
        "items": [
            "tiền nhà", "tiền trọ", "tiền phòng", "thuê nhà", "thuê phòng", "tiền điện", "tiền nước", "hóa đơn điện",
            "hóa đơn nước", "điện nước", "internet", "wifi", "cước internet", "tiền mạng", "tiền wifi", "nạp điện thoại",
            "nạp tiền điện thoại", "nạp thẻ", "thẻ cào", "gói data", "gói 4g", "4g", "5g", "cước điện thoại", "cước di động",
            "sim", "tiền rác", "phí vệ sinh", "phí dịch vụ chung cư", "phí quản lý", "tiền gas", "bình gas", "đổi gas",
            "truyền hình cáp", "gói cước", "icloud", "google one", "phí gửi xe chung cư", "tiền điện tháng",
            "tiền nước tháng", "tiền phòng tháng", "đặt cọc phòng",
        ],
        "brands": [
            "evn", "điện lực", "cấp nước", "sawaco", "viettel", "vinaphone", "mobifone", "vietnamobile", "fpt telecom",
            "vnpt", "k+", "vtvcab", "momo nạp", "payoo",
        ],
    },
    "fun": {
        "verbs": ["xem", "đi", "chơi", "mua", "nạp", "đặt", "thuê", "tập"],
        "items": [
            "xem phim", "vé xem phim", "vé phim", "bắp nước rạp phim", "karaoke", "hát karaoke", "game", "nạp game",
            "mua game", "skin game", "thẻ game", "netflix", "spotify", "youtube premium", "du lịch", "khách sạn",
            "homestay", "resort", "vé tham quan", "công viên", "khu vui chơi", "bowling", "bi a", "billiards", "đá bóng",
            "thuê sân bóng", "sân cầu lông", "cầu lông", "pickleball", "tennis", "gym", "tập gym", "phí gym", "yoga",
            "bơi", "vé bơi", "hồ bơi", "concert", "vé ca nhạc", "vé concert", "bar", "pub", "truyện tranh", "sách truyện",
            "board game", "escape room", "sở thú", "bảo tàng", "đi chơi", "picnic", "cắm trại", "trò chơi", "lego",
            "mô hình", "gacha", "vé sự kiện", "phòng net", "chơi net", "đi phượt", "tour",
        ],
        "brands": [
            "cgv", "lotte cinema", "galaxy cinema", "bhd", "beta cinemas", "steam", "garena", "liên quân", "playstation",
            "nintendo", "vinwonders", "sun world", "đầm sen", "suối tiên", "agoda", "traveloka", "booking", "airbnb",
            "california fitness", "elite fitness", "icarus",
        ],
    },
    "other": {
        "verbs": ["gửi", "chuyển", "cho", "trả", "đóng", "nộp", "ủng hộ", "mừng"],
        "items": [
            "quà sinh nhật", "quà tặng", "mua quà", "mừng cưới", "đám cưới", "tiền mừng", "đám hiếu", "phúng điếu",
            "từ thiện", "ủng hộ", "quyên góp", "chuyển tiền cho mẹ", "gửi tiền về nhà", "gửi tiền bố mẹ", "trả nợ",
            "cho bạn mượn", "trả tiền bạn", "lì xì", "đóng quỹ lớp", "quỹ lớp", "đoàn phí", "quỹ đoàn", "cắt tóc",
            "gội đầu", "nhuộm tóc", "giặt ủi", "giặt sấy", "sửa điện thoại", "thay màn hình", "phí chuyển khoản",
            "phí ngân hàng", "phí thường niên", "tiền phạt", "nộp phạt", "công chứng", "làm giấy tờ", "lệ phí",
            "tiền tip", "thức ăn cho mèo", "thức ăn cho chó", "cát vệ sinh mèo", "thú y", "chi tiêu khác", "linh tinh",
            "tiền lặt vặt", "góp tiền", "chia tiền", "đi chùa", "cúng", "hoa", "mua hoa", "vé số", "đánh đề",
        ],
        "brands": ["momo", "zalopay", "chuyển khoản", "ngân hàng", "tiệm tóc", "tiệm giặt"],
    },
}

# Cách nói số tiền (không mang thông tin danh mục, chỉ để câu train giống câu thật).
AMOUNTS = [
    "15k", "20k", "25k", "35k", "40k", "50k", "120k", "200k", "1tr", "1tr2", "2 triệu", "15 nghìn", "30 nghìn",
    "45 ngàn", "100 nghìn", "hai chục", "ba chục", "năm chục", "5 xị", "2 xị", "nửa triệu", "ba lăm nghìn",
    "45.000đ", "120.000 vnd", "35000", "250.000", "1.500.000đ", "",
]

WHO = ["", "", "", "cho mẹ", "cho em", "với bạn", "cho cả nhóm", "hôm nay", "sáng nay", "tối qua", "hôm qua"]

# Gộp nhóm chi phí cố định vào "Khác".
for _k in ("verbs", "items", "brands"):
    SEED["other"][_k] = SEED["other"][_k] + [w for w in SEED["_fixed"][_k] if w not in SEED["other"][_k]]
del SEED["_fixed"]
