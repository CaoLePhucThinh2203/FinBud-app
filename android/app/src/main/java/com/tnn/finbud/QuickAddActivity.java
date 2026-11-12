package com.tnn.finbud;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Bảng "Nhập nhanh" mở từ nút "Nói / gõ" trên widget: trượt lên ở phần dưới màn hình, phía sau vẫn là
 * màn hình chính, không phải khởi động WebView của app nên mở gần như tức thì.
 *
 * - Tách số tiền + phân loại: QuickParser (giống hệt bản JS, dùng chung receipt_model.json / receipt_lexicon.json).
 * - Giọng nói: VoiceEngine, cùng hệ thống với nút Nói trong app (Android / trên máy / Vosk dự phòng,
 *   theo cài đặt "Nhận giọng nói" của app). Mở từ nút Nói của widget thì nghe ngay.
 * - Lưu: giao dịch vào hàng chờ (FinBudWidget.addPending); widget cập nhật ngay;
 *   lần mở app sau, index.html lấy hàng chờ vào Lịch sử.
 */
public class QuickAddActivity extends Activity {

    private static final int REQ_MIC = 7;
    static final String EXTRA_LISTEN = "listen";             // mở từ nút Nói của widget: nghe ngay
    private static volatile QuickParser.Classifier classifier;   // nạp một lần, dùng lại cho các lần mở sau

    // Bảng màu giống app (sáng / tối)
    private int cPh, cCard, cTx, cMut, cLine, cPri, cPri2, cBad;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText input, amountField, noteField;
    private TextView micBtn, status;
    private LinearLayout result, chipRow, sheet;
    private final Map<String, TextView> chips = new HashMap<>();
    private String cat = "other";
    private boolean listening, deviceOnly;              // deviceOnly: cài đặt "Chỉ trên máy" trong app
    private String voiceSource;                          // null = gõ tay
    private VoiceEngine voice;
    private final Runnable analyzeLater = this::analyze;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SpeechHelper.installCrashLog(this, "widget");
        JSONObject data = FinBudWidget.readData(this);
        if (data == null || !data.optBoolean("ready")) {          // chưa thiết lập ngân sách -> mở app
            Toast.makeText(this, "Mở FinBud để thiết lập ngân sách trước", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        palette(data.optString("theme", "auto"));
        deviceOnly = "device".equals(data.optString("voice", "best"));
        setContentView(buildUi());
        voice = new VoiceEngine(this, voiceCallback());
        VoiceEngine.prepare(this, deviceOnly);
        loadClassifier();
        if (getIntent().getBooleanExtra(EXTRA_LISTEN, false)) {
            ui.postDelayed(this::toggleMic, 250);               // nút Nói trên widget: bắt đầu nghe luôn
        } else {
            input.requestFocus();
            ui.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && !listening) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }, 150);
        }
    }

    // ---------------- Giao diện ----------------

    private void palette(String theme) {
        boolean dark = "dark".equals(theme) || (!"light".equals(theme)
            && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        cPh = Color.parseColor(dark ? "#101917" : "#FFFFFF");
        cCard = Color.parseColor(dark ? "#18231F" : "#F3F6F5");
        cTx = Color.parseColor(dark ? "#E8F1EE" : "#14211E");
        cMut = Color.parseColor(dark ? "#8EA39D" : "#6B7C77");
        cLine = Color.parseColor(dark ? "#2A3A37" : "#DDE5E3");
        cPri = Color.parseColor(dark ? "#2BB894" : "#0F9D7A");
        cPri2 = Color.parseColor(dark ? "#123A31" : "#D9F3EC");
        cBad = Color.parseColor("#D9402F");
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable round(int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText field(String hint, float sp) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        e.setTextColor(cTx);
        e.setHintTextColor(cMut);
        e.setBackground(null);
        e.setSingleLine(true);
        return e;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#80000000"));
        root.setOnClickListener(v -> close());

        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);                                  // chạm trong bảng không đóng
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cPh);
        float r = dp(24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        int pad = dp(16);
        sheet.setPadding(pad, dp(8), pad, pad);
        root.addView(sheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        View grab = new View(this);
        grab.setBackground(round(cLine, 2, 0));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(36), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(12);
        sheet.addView(grab, gp);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Nhập nhanh", 18, cTx, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = text("Đóng", 14, cPri, true);
        close.setPadding(dp(8), dp(6), 0, dp(6));
        close.setOnClickListener(v -> close());
        head.addView(close);
        sheet.addView(head);

        LinearLayout fld = new LinearLayout(this);
        fld.setGravity(Gravity.CENTER_VERTICAL);
        fld.setPadding(dp(12), dp(4), dp(4), dp(4));
        fld.setBackground(round(cCard, 14, cLine));
        input = field("ăn bún bò 35k", 15);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(analyzeLater);
                ui.postDelayed(analyzeLater, 350);
            }
        });
        input.setOnEditorActionListener((v, id, e) -> {
            analyze();
            return false;
        });
        fld.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        micBtn = text("Nói", 14, Color.WHITE, true);
        micBtn.setGravity(Gravity.CENTER);
        micBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        micBtn.setBackground(round(cPri, 10, 0));
        micBtn.setOnClickListener(v -> toggleMic());
        fld.addView(micBtn);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = dp(14);
        sheet.addView(fld, fp);

        status = text("", 12, cMut, false);
        status.setVisibility(View.GONE);
        status.setPadding(dp(2), dp(8), 0, 0);
        sheet.addView(status);

        result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setGravity(Gravity.CENTER_HORIZONTAL);
        result.setPadding(dp(12), dp(10), dp(12), dp(12));
        result.setBackground(round(cCard, 16, 0));
        result.setVisibility(View.GONE);
        amountField = field("Số tiền", 28);
        amountField.setTypeface(Typeface.DEFAULT_BOLD);
        amountField.setGravity(Gravity.CENTER);
        amountField.setInputType(InputType.TYPE_CLASS_NUMBER);
        result.addView(amountField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        for (String[] c : QuickParser.CATS) chipRow.addView(chip(c[0], c[1], Color.parseColor(c[2])));
        hs.addView(chipRow);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(6);
        hp.bottomMargin = dp(4);
        result.addView(hs, hp);

        noteField = field("Ghi chú", 14);
        noteField.setGravity(Gravity.CENTER);
        noteField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        result.addView(noteField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(12);
        sheet.addView(result, rp);

        TextView save = text("Lưu", 15, Color.WHITE, true);
        save.setGravity(Gravity.CENTER);
        save.setBackground(round(cPri, 12, 0));
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        sp.topMargin = dp(12);
        sheet.addView(save, sp);

        // Bàn phím và thanh điều hướng: đẩy bảng lên (Android 11+ vẽ tràn viền nên phải tự tính)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, ins) -> {
                int b = ins.getInsets(WindowInsets.Type.ime() | WindowInsets.Type.navigationBars()).bottom;
                sheet.setPadding(pad, dp(8), pad, pad + b);
                return WindowInsets.CONSUMED;
            });
        }

        sheet.setTranslationY(dp(420));                            // trượt lên khi mở
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(150).start();
        sheet.animate().translationY(0).setDuration(220).start();
        return root;
    }

    private TextView chip(String key, String label, int color) {
        TextView t = text(label, 13, cTx, false);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setSize(dp(8), dp(8));
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        t.setCompoundDrawablePadding(dp(6));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> pick(key));
        chips.put(key, t);
        return t;
    }

    private void pick(String key) {
        cat = key;
        for (Map.Entry<String, TextView> e : chips.entrySet()) {
            boolean on = e.getKey().equals(key);
            e.getValue().setBackground(round(on ? cPri2 : cPh, 999, on ? cPri : cLine));
            e.getValue().setTextColor(on ? cPri : cTx);
            e.getValue().setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void showStatus(String s) {
        status.setText(s);
        status.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------------- Tách số tiền, phân loại ----------------

    private void analyze() {
        ui.removeCallbacks(analyzeLater);
        String t = input.getText().toString().trim();
        if (t.isEmpty()) {
            result.setVisibility(View.GONE);
            if (!listening) showStatus("");
            return;
        }
        QuickParser.Amount p = QuickParser.parseAmt(t);
        if (p.a <= 0) {
            result.setVisibility(View.GONE);
            if (!listening) showStatus("Chưa thấy số tiền. Thử nói rõ hơn, ví dụ “cà phê 15k”.");
            return;
        }
        if (!listening) showStatus("");
        amountField.setText(String.valueOf(p.a));
        QuickParser.Classifier c = classifier;
        pick(c != null ? c.categorize(t) : QuickParser.classifyRx(t));
        noteField.setText(QuickParser.noteOf(p));
        result.setVisibility(View.VISIBLE);
    }

    /** Nạp mô hình phân loại từ www (Capacitor chép vào assets/public) ở luồng nền. */
    private void loadClassifier() {
        if (classifier != null) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject cat = new JSONObject(asset("public/receipt_model.json")).getJSONObject("cat");
                JSONObject lex = new JSONObject(asset("public/receipt_lexicon.json"));
                JSONArray cls = cat.getJSONArray("classes");
                String[] classes = new String[cls.length()];
                for (int i = 0; i < classes.length; i++) classes[i] = cls.getString(i);
                JSONObject v = cat.getJSONObject("vocab");
                Map<String, Integer> vocab = new HashMap<>(v.length() * 2);
                for (Iterator<String> it = v.keys(); it.hasNext(); ) {
                    String k = it.next();
                    vocab.put(k, v.getInt(k));
                }
                double[] idf = nums(cat.getJSONArray("idf")), intercept = nums(cat.getJSONArray("intercept"));
                JSONArray co = cat.getJSONArray("coef");
                double[][] coef = new double[co.length()][];
                for (int i = 0; i < coef.length; i++) coef[i] = nums(co.getJSONArray(i));
                List<String> cats = new ArrayList<>();
                JSONArray ca = lex.getJSONArray("categories");
                for (int i = 0; i < ca.length(); i++) cats.add(ca.getString(i));
                LinkedHashMap<String, List<Object[]>> kw = new LinkedHashMap<>();
                JSONObject ck = lex.getJSONObject("cat_kw");
                for (Iterator<String> it = ck.keys(); it.hasNext(); ) {   // org.json giữ thứ tự khoá như trong file
                    String k = it.next();
                    JSONArray a = ck.getJSONArray(k);
                    List<Object[]> l = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) l.add(new Object[]{a.getJSONArray(i).getString(0), a.getJSONArray(i).getDouble(1)});
                    kw.put(k, l);
                }
                classifier = new QuickParser.Classifier(classes, vocab, idf, coef, intercept, cats, kw);
                ui.post(() -> { if (result.getVisibility() == View.VISIBLE) analyze(); });
            } catch (Exception e) {
                // Không có mô hình: vẫn chạy bằng luật đơn giản (classifyRx)
            }
        });
    }

    private String asset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static double[] nums(JSONArray a) throws Exception {
        double[] d = new double[a.length()];
        for (int i = 0; i < d.length; i++) d[i] = a.getDouble(i);
        return d;
    }

    // ---------------- Lưu ----------------

    private void save() {
        long a;
        try {
            a = Long.parseLong(amountField.getText().toString().trim());
        } catch (Exception e) {
            a = 0;
        }
        if (result.getVisibility() != View.VISIBLE || a <= 0) {
            showStatus("Số tiền chưa hợp lệ");
            return;
        }
        String note = noteField.getText().toString().trim();
        Calendar c = Calendar.getInstance();
        try {
            JSONObject tx = new JSONObject();
            tx.put("id", System.currentTimeMillis());
            tx.put("a", a);
            tx.put("c", cat);
            tx.put("n", note.isEmpty() ? "Chi tiêu" : note);
            tx.put("s", voiceSource != null ? voiceSource : "Nhập nhanh");
            tx.put("d", String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)));
            FinBudWidget.addPending(this, tx);
        } catch (Exception e) {
            showStatus("Không lưu được, thử lại nhé");
            return;
        }
        FinBudWidget.updateAll(this);
        Toast.makeText(this, "Đã lưu " + FinBudWidget.fmt(a) + " · hạn mức đã cập nhật", Toast.LENGTH_SHORT).show();
        close();
    }

    private void close() {
        if (voice != null) voice.release();
        finish();
        overridePendingTransition(0, 0);
    }

    // ---------------- Giọng nói: dùng chung VoiceEngine với app ----------------

    private void toggleMic() {
        if (listening) {
            voice.stop();                                       // vẫn nhận nốt phần đã nói
            setMicUi(false);
            showStatus("Đang xử lý…");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startMic();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_MIC && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startMic();
        else if (code == REQ_MIC) showStatus("Cần cấp quyền micro để nói");
    }

    private void startMic() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        try {
            setMicUi(true);
            showStatus("Đang nghe…");
            voice.start(deviceOnly);
        } catch (Exception e) {                                  // không để pop-up văng ra ngoài
            setMicUi(false);
            SpeechHelper.recordError(this, "pop-up: bật nhận giọng", SpeechHelper.stack(e));
            showStatus("Không bật được nhận giọng nói. Gõ tay nhé");
        }
    }

    private VoiceEngine.Callback voiceCallback() {
        return new VoiceEngine.Callback() {
            public void onState(String state) {
                if ("loading".equals(state)) showStatus("Đang nạp nhận giọng trên máy…");
                else if ("listening".equals(state)) showStatus("Đang nghe…");
                else {                                           // "idle": kết quả / lỗi (nếu có) báo ngay sau đó
                    setMicUi(false);
                    showStatus("");
                }
            }

            public void onPartial(String text) {
                input.setText(text);
                input.setSelection(text.length());
            }

            public void onResult(String text, String engine) {
                setMicUi(false);
                showStatus("");
                voiceSource = "vosk".equals(engine) ? "Voice Offline" : "Voice";
                input.setText(text);
                input.setSelection(text.length());
                analyze();
            }

            public void onInfo(String message) {
                Toast.makeText(QuickAddActivity.this, message, Toast.LENGTH_SHORT).show();
            }

            public void onError(String message, int code) {
                setMicUi(false);
                showStatus(message != null ? message + ". Bấm Nói để thử lại hoặc gõ tay" : "");
            }
        };
    }

    private void setMicUi(boolean on) {
        listening = on;
        micBtn.setText(on ? "Dừng" : "Nói");
        micBtn.setBackground(round(on ? cBad : cPri, 10, 0));
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (voice != null) voice.release();                     // rời pop-up (về màn hình chính...) thì nhả micro
        if (!isChangingConfigurations() && !isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (voice != null) voice.release();
        super.onDestroy();
    }
}

