package com.soham.crookedguess;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // our own plugin (LAN Versus); plugins from npm (AdMob) are registered automatically
        registerPlugin(LanServerPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
