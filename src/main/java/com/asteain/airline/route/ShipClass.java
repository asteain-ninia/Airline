package com.asteain.airline.route;

import java.util.Locale;

/**
 * The size class of an aircraft (the user's ruling of 2026-10-14: 小型機・中型機・大型機, "small", "medium", "large").
 * Each class has an outline every design of it fits in, seen from the aircraft's own block: so far ahead and astern,
 * so far to either side, and so high. Everything the air network and the airfields keep free for an aircraft follows
 * from these numbers.
 */
public enum ShipClass {
    /**
     * 9 long, 9 wide, 10 high: koeda, the first design, the white sail. (9 high until 2026-10-15: the user's ruling
     * "小型の枠を 10 段に広げる" for koeda, whose envelope reaches the tenth layer.)
     */
    SMALL(4, 4, 10),
    /** 15 long, 11 wide, 13 high (STRATUS, 13 x 9 x 12, fits). */
    MEDIUM(7, 5, 13),
    /** 25 long, 15 wide, 17 high. */
    LARGE(12, 7, 17);

    /** Blocks ahead of (and astern of) the aircraft's own block, to either side of it, and from it upward. */
    public final int halfLength, halfWidth, height;

    ShipClass(int halfLength, int halfWidth, int height) {
        this.halfLength = halfLength;
        this.halfWidth = halfWidth;
        this.height = height;
    }

    /** From the middle of the own block to the outline's farthest corner, seen from above. */
    public double halfDiagonal() {
        return Math.hypot(halfLength + 0.5, halfWidth + 0.5);
    }

    public double halfHeight() {
        return height / 2.0;
    }

    public boolean atLeast(ShipClass other) {
        return ordinal() >= other.ordinal();
    }

    /** The name in data files ("small", "medium", "large"). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Read from a design file ("small", "medium", "large"; a design that does not say is small). */
    public static ShipClass of(String key) {
        for (final ShipClass c : values()) if (c.key().equals(key)) return c;
        throw new IllegalArgumentException("no class '" + key + "' (small, medium or large)");
    }

    public static ShipClass smaller(ShipClass a, ShipClass b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
