package com.asteain.airline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExportMathTest {
    @Test
    void surplusIsStockMinusKeepMinusInFlight() {
        assertEquals(128, ExportMath.surplus(192, 1, 64, 0));
        assertEquals(64, ExportMath.surplus(192, 1, 64, 64));
        assertEquals(0, ExportMath.surplus(64, 1, 64, 0));
        assertEquals(0, ExportMath.surplus(10, 5, 64, 0));
        assertEquals(176, ExportMath.surplus(192, 1, 16, 0));
    }

    @Test
    void missingIsWantMinusRackMinusInFlight() {
        assertEquals(100, ExportMath.missing(100, 0, 0));
        assertEquals(36, ExportMath.missing(100, 64, 0));
        assertEquals(0, ExportMath.missing(100, 64, 36));
        assertEquals(0, ExportMath.missing(10, 64, 0));
    }

    @Test
    void nextRequestNeverExceedsSurplusNeedOrOneStack() {
        // want 128, nothing collected, 3 stacks in stock, keep 1: one stack per request
        assertEquals(64, ExportMath.nextRequest(128, 0, 0, 192, 1, 64));
        // the first stack is on the way: one more stack of surplus is left
        assertEquals(64, ExportMath.nextRequest(128, 0, 64, 192, 1, 64));
        // want 100, 64 already on the racks: only the missing 36
        assertEquals(36, ExportMath.nextRequest(100, 64, 0, 128, 1, 64));
        // surplus smaller than the need
        assertEquals(6, ExportMath.nextRequest(100, 0, 0, 70, 1, 64));
        // nothing to spare, or nothing wanted
        assertEquals(0, ExportMath.nextRequest(100, 0, 0, 64, 1, 64));
        assertEquals(0, ExportMath.nextRequest(0, 0, 0, 640, 1, 64));
        // already collected enough
        assertEquals(0, ExportMath.nextRequest(64, 64, 0, 640, 1, 64));
    }
}
