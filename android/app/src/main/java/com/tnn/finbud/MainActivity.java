package com.tnn.finbud;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VoskPlugin.class);            // Nhận giọng nói offline (Vosk)
        registerPlugin(ReceiptScannerPlugin.class);  // OCR hóa đơn offline (ML Kit)
        registerPlugin(WidgetPlugin.class);          // Widget màn hình chính
        super.onCreate(savedInstanceState);
    }
}
