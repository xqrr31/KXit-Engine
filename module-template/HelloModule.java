package com.example.kxit;

import com.rookmender.kxit.module.KXitModule;
import com.rookmender.kxit.module.KXitModuleApi;

/** Minimal example for a compiled KXit .dex module. */
public final class HelloModule implements KXitModule {
    private KXitModuleApi api;

    public HelloModule() { }

    @Override
    public void onLoad(KXitModuleApi api) {
        this.api = api;
        api.log("HelloModule loaded. Grass at spawn: " + api.getBlock(24, 5, 41));
    }

    @Override
    public void onUpdate(float deltaSeconds) {
        // Keep module updates small; this callback runs once per rendered frame.
    }

    @Override
    public void onDispose() {
        if (api != null) api.log("HelloModule unloaded.");
    }
}
