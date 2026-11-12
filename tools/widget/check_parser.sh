#!/usr/bin/env bash
# So bản Java (QuickParser, dùng cho bảng nhập nhanh từ widget) với bản JS của app trên cùng danh sách câu.
# Cần JDK và Node. Chạy từ thư mục gốc dự án:  bash tools/widget/check_parser.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/tools/widget/build"
mkdir -p "$OUT"

# Câu thử: số tiền nhiều kiểu + toàn bộ bộ test phân loại
{ cat "$ROOT/tools/widget/phrases.txt"; tail -n +2 "$ROOT/tools/category/test_set.csv" | sed 's/,[a-z]*\r\{0,1\}$//'; } > "$OUT/phrases.txt"

javac -encoding UTF-8 -d "$OUT" "$ROOT/android/app/src/main/java/com/tnn/finbud/QuickParser.java" "$ROOT/android/app/src/main/java/com/tnn/finbud/VoiceNumbers.java" "$ROOT/tools/widget/ParserCheck.java"
java -Dfile.encoding=UTF-8 -cp "$OUT" ParserCheck "$ROOT" "$OUT/phrases.txt" > "$OUT/java.tsv"
node "$ROOT/tools/widget/parser_js.js" "$ROOT" "$OUT/phrases.txt" > "$OUT/js.tsv"

if diff "$OUT/js.tsv" "$OUT/java.tsv" > "$OUT/diff.txt"; then
  echo "KHỚP: Java và JS cho cùng kết quả trên $(wc -l < "$OUT/js.tsv") câu"
else
  echo "LỆCH (dòng < là JS, > là Java):"; cat "$OUT/diff.txt"; exit 1
fi
