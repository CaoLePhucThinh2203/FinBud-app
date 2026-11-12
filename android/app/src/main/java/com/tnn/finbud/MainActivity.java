package com.tnn.finbud;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VoicePlugin.class);           // Nhận giọng nói (Android / trên máy / Vosk), dùng chung với widget
        registerPlugin(ReceiptScannerPlugin.class);  // OCR hóa đơn offline (ML Kit)
        registerPlugin(WidgetPlugin.class);          // Widget màn hình chính
        super.onCreate(savedInstanceState);
    }
}
