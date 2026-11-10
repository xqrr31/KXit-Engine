package com.rookmender.kxit.module;

/** Narrow host API exposed to optional KXit modules. */
public final class KXitModuleApi {
    public interface Host {
        int getBlock(int x, int y, int z);
        void setBlock(int x, int y, int z, int blockId);
        void setDaylight(float amount);
        void log(String message);
    }

    private final Host host;

    public KXitModuleApi(Host host) {
        this.host = host;
    }

    public int getBlock(int x, int y, int z) { return host.getBlock(x, y, z); }
    public void setBlock(int x, int y, int z, int blockId) { host.setBlock(x, y, z, blockId); }
    public void setDaylight(float amount) { host.setDaylight(amount); }
    public void log(String message) { host.log(message); }
}
