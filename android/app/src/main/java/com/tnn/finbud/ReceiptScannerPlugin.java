package com.tnn.finbud;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * OCR hóa đơn chạy hoàn toàn trên máy bằng ML Kit (bộ chữ Latin, gồm tiếng Việt).
 * JS gọi: Capacitor.Plugins.ReceiptScanner.recognize({ image: "<base64 jpeg, không có tiền tố data:>" })
 * Trả về: { lines: [{ text, left, top, right, bottom }], width, height }
 * Phần trích tổng tiền/ngày/người bán/danh mục nằm ở www/receipt-engine.js.
 */
@CapacitorPlugin(name = "ReceiptScanner")
public class ReceiptScannerPlugin extends Plugin {

    private TextRecognizer recognizer;

    private TextRecognizer getRecognizer() {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        return recognizer;
    }

    @PluginMethod
    public void recognize(final PluginCall call) {
        String b64 = call.getString("image");
        if (b64 == null || b64.isEmpty()) {
            call.reject("Thiếu ảnh");
            return;
        }
        Bitmap bmp = null;
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            bmp = null;
        }
        if (bmp == null) {
            call.reject("Không đọc được ảnh");
            return;
        }
        final int w = bmp.getWidth();
        final int h = bmp.getHeight();
        InputImage image = InputImage.fromBitmap(bmp, 0);

        getRecognizer().process(image)
            .addOnSuccessListener(result -> {
                JSArray arr = new JSArray();
                for (Text.TextBlock block : result.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        Rect r = line.getBoundingBox();
                        if (r == null) continue;
                        JSObject o = new JSObject();
                        o.put("text", line.getText());
                        o.put("left", r.left);
                        o.put("top", r.top);
                        o.put("right", r.right);
                        o.put("bottom", r.bottom);
                        arr.put(o);
                    }
                }
                JSObject ret = new JSObject();
                ret.put("lines", arr);
                ret.put("width", w);
                ret.put("height", h);
                call.resolve(ret);
            })
            .addOnFailureListener(e -> call.reject("OCR lỗi: " + e.getMessage()));
    }
}
