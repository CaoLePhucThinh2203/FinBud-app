import com.tnn.finbud.QuickParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chạy QuickParser (bản Java dùng cho bảng nhập nhanh từ widget) trên danh sách câu và in ra TSV
 * "câu \t số tiền \t ghi chú \t danh mục" để so với bản JS (parser_js.js). Không cần Android SDK.
 * Dùng qua tools/widget/check_parser.sh
 */
public class ParserCheck {

    public static void main(String[] args) throws Exception {
        String root = args[0];
        Map<String, Object> model = asMap(new Json(read(root + "/www/receipt_model.json")).parse());
        Map<String, Object> lex = asMap(new Json(read(root + "/www/receipt_lexicon.json")).parse());
        Map<String, Object> cat = asMap(model.get("cat"));

        List<Object> cls = asList(cat.get("classes"));
        String[] classes = cls.stream().map(Object::toString).toArray(String[]::new);
        Map<String, Integer> vocab = new java.util.HashMap<>();
        for (Map.Entry<String, Object> e : asMap(cat.get("vocab")).entrySet()) vocab.put(e.getKey(), ((Number) e.getValue()).intValue());
        double[] idf = nums(asList(cat.get("idf")));
        double[] intercept = nums(asList(cat.get("intercept")));
        List<Object> rows = asList(cat.get("coef"));
        double[][] coef = new double[rows.size()][];
        for (int i = 0; i < rows.size(); i++) coef[i] = nums(asList(rows.get(i)));

        List<String> categories = new ArrayList<>();
        for (Object o : asList(lex.get("categories"))) categories.add(o.toString());
        LinkedHashMap<String, List<Object[]>> catKw = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : asMap(lex.get("cat_kw")).entrySet()) {
            List<Object[]> l = new ArrayList<>();
            for (Object kw : asList(e.getValue())) l.add(asList(kw).toArray());
            catKw.put(e.getKey(), l);
        }
        QuickParser.Classifier c = new QuickParser.Classifier(classes, vocab, idf, coef, intercept, categories, catKw);

        StringBuilder out = new StringBuilder();
        for (String line : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            QuickParser.Amount p = QuickParser.parseAmt(line);
            out.append(line).append('\t').append(p.a).append('\t').append(QuickParser.noteOf(p)).append('\t')
                .append(c.categorize(line)).append('\t').append(QuickParser.classifyRx(line)).append('\n');
        }
        System.out.write(out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }

    static String read(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    static double[] nums(List<Object> l) {
        double[] d = new double[l.size()];
        for (int i = 0; i < d.length; i++) d[i] = ((Number) l.get(i)).doubleValue();
        return d;
    }

    /** Bộ đọc JSON tối giản, chỉ để chạy kiểm tra (trong app dùng org.json). */
    static final class Json {
        final String s;
        int i;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    i++; // :
                    m.put(k, parse());
                    ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(parse());
                    ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }

        String str() {
            StringBuilder b = new StringBuilder();
            i++; // "
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
