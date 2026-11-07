package com.tnn.finbud;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VoskPlugin.class); // Đăng ký VoskPlugin
        super.onCreate(savedInstanceState);
    }
}