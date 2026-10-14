package com.asteain.airline;

/** The arithmetic of the export collection, kept free of game classes so it can be unit-tested. */
public final class ExportMath {
    private ExportMath() {}

    /** What the warehouses can spare: stock minus the amount to keep minus what is already promised to the airfield. Never negative. */
    public static int surplus(int warehouseCount, int keepStacks, int maxStackSize, int inFlight) {
        long spare = (long) warehouseCount - (long) keepStacks * maxStackSize - inFlight;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, spare));
    }

    /** How many items are still missing on the export racks for what other colonies want. Never negative. */
    public static int missing(int wantedByOthers, int onExportRacks, int inFlight) {
        long need = (long) wantedByOthers - onExportRacks - inFlight;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, need));
    }

    /**
     * The size of the next request: the missing amount, capped by the surplus and by one stack (one request per item at a time).
     * Zero means "do not ask".
     */
    public static int nextRequest(int wantedByOthers, int onExportRacks, int inFlight, int warehouseCount, int keepStacks, int maxStackSize) {
        int need = missing(wantedByOthers, onExportRacks, inFlight);
        int spare = surplus(warehouseCount, keepStacks, maxStackSize, inFlight);
        return Math.max(0, Math.min(Math.min(need, spare), maxStackSize));
    }
}
