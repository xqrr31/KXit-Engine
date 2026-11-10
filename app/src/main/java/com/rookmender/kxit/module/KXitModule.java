package com.rookmender.kxit.module;

/** Extension point for trusted, startup-loaded KXit DEX modules. */
public interface KXitModule {
    void onLoad(KXitModuleApi api);
    default void onUpdate(float deltaSeconds) { }
    default void onDispose() { }
}
