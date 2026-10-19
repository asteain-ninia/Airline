package com.asteain.airline.flight;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * Fuel. The engines of an aircraft burn furnace fuel, tick for tick, as long as they have any (the burner needs
 * none); an engine cannot be switched off. So the fuel is not kept in the engines: it lies in the aircraft's fuel store
 * (the inventory of its own block, {@link FuelStore}; the user's ruling of 2026-10-14), is taken out of it one item at a
 * time as the engines need it, and what is taken out and not burnt yet is kept as a number on the block. "The tank" is
 * the two together ({@link #total}), and
 *
 * <ul>
 * <li>while the aircraft flies, the tank feeds every engine a few seconds at a time;</li>
 * <li>when it has landed, what the engines have not burnt goes back into the tank, and they stand still: an aircraft on
 * a pad burns nothing;</li>
 * <li>at home the pilot carries fuel from the airfield's racks to the aircraft whenever the tank is not well filled
 * (the same walk as with cargo); a player can also put fuel in by hand (use the aircraft's own block with it);</li>
 * <li>a flight is only begun with enough in the tank for the whole of it and half as much again;</li>
 * <li>an aircraft whose tank and engines are empty in the air is in distress: it stays where it is, fixed, with its
 * pilot on board, until somebody brings fuel (the user's ruling of 2026-10-06: rescue is the player bringing fuel).</li>
 * </ul>
 *
 * Nothing is made or lost: every item put in is counted with the burn time the engines would get from it.
 */
final class Fuel {
    private Fuel() {}

    /** The engines are topped up to this many ticks each time they are looked at (once a second). */
    private static final int FEED = 100;

    /** Ticks one item of this kind lets one engine burn; 0 for what airfields do not take as fuel. */
    static int burn(Item item) {
        return Cargo.fuelItems().contains(item) ? Math.max(0, new ItemStack(item).getBurnTime(null)) : 0;
    }

    static long capacity() {
        return Cargo.fuelCapacity();
    }

    /** Everything the aircraft has to burn (ticks of one engine): what was taken out of the store already, and what the store holds. */
    static long total(AircraftBlockEntity be) {
        long sum = be.fuel;
        for (int slot = 0; slot < be.store.getContainerSize(); slot++) {
            final ItemStack stack = be.store.getItem(slot);
            if (!stack.isEmpty()) sum += (long) burn(stack.getItem()) * stack.getCount();
        }
        return sum;
    }

    /** One item more out of the store, for the engines; false when the store holds nothing to burn. */
    private static boolean takeOne(AircraftBlockEntity be) {
        for (int slot = 0; slot < be.store.getContainerSize(); slot++) {
            final ItemStack stack = be.store.getItem(slot);
            final int each = stack.isEmpty() ? 0 : burn(stack.getItem());
            if (each <= 0) continue;
            be.store.removeItem(slot, 1);
            be.fuel += each;
            return true;
        }
        return false;
    }

    /** Fills the store with so much burn time (an aircraft made by command comes with a full tank), in the first kind of fuel airfields take. */
    static void fill(AircraftBlockEntity be, long ticks) {
        for (final Item item : Cargo.fuelItems()) {
            final int each = burn(item);
            if (each <= 0) continue;
            long left = (ticks + each - 1) / each;
            while (left > 0) {
                final ItemStack stack = new ItemStack(item, (int) Math.min(left, item.getDefaultMaxStackSize()));
                final int fits = Math.min(stack.getCount(), be.store.room(stack));
                if (fits <= 0) break;
                be.store.addItem(stack.copyWithCount(fits));
                left -= fits;
            }
            return;
        }
        be.fuel += ticks;      // (no fuel item is known: kept as a number)
    }

    /** Takes everything out of the tank: the store and what was taken out of it. */
    static void empty(AircraftBlockEntity be) {
        be.store.clearContent();
        be.fuel = 0;
        be.setChanged();
    }

    /** At home the tank is filled up again when it holds less than this. */
    static boolean low(AircraftBlockEntity be) {
        return total(be) < capacity() * 3 / 4;
    }

    /** What a flight of so many ticks needs in the tank before it is begun: every engine all the way, half as much again, and two minutes. */
    static long need(AircraftType type, long flightTicks) {
        return type.engines.length * (flightTicks * 3 / 2 + 2400);
    }

    /** Minutes of flight so much in the tank is for a design (the more engines, the fewer). */
    static long minutes(AircraftType type, long fuel) {
        return fuel / type.engines.length / 1200;
    }

    /** Minutes of flight the tank holds. */
    static long minutes(AircraftBlockEntity be) {
        return minutes(AircraftType.of(be.type), total(be));
    }

    // ---- in the air, and after landing --------------------------------------------------------------------------

    /** In the air, once a second: the engines are kept burning out of the tank. */
    static void feed(Craft c, AircraftBlockEntity be) {
        for (int side = 0; side < c.engines(); side++) {
            final int burning = c.engineBurn(side);
            if (burning < 0 || burning >= FEED) continue;
            if (be.fuel < FEED - burning) takeOne(be);
            if (be.fuel <= 0) continue;
            final int more = (int) Math.min(FEED - burning, be.fuel);
            c.engineBurn(side, burning + more);
            be.fuel -= more;
        }
    }

    /** Whether nothing burns any more and nothing is left to burn: the engines stand, the tank is empty. */
    static boolean dry(Craft c, AircraftBlockEntity be) {
        if (total(be) > 0) return false;
        for (int side = 0; side < c.engines(); side++) if (c.engineBurn(side) != 0 || !c.engineStack(side).isEmpty()) return false;
        return true;
    }

    /** On a pad: what the engines hold and have not burnt goes back into the tank (also fuel somebody put into an engine by hand). */
    static void drain(Craft c, AircraftBlockEntity be) {
        for (int side = 0; side < c.engines(); side++) {
            final int burning = c.engineBurn(side);
            // (An engine that burns a fuel without end, a creative one, is left alone.)
            if (burning > 0 && burning < 1_000_000) {
                be.fuel += burning;
                c.engineBurn(side, 0);
            }
            final ItemStack held = c.engineStack(side);
            final int each = held.isEmpty() ? 0 : Math.max(0, held.getBurnTime(null));
            if (each > 0 && each < 1_000_000) {
                be.fuel += (long) each * held.getCount();
                c.engineStack(side, ItemStack.EMPTY);
            }
        }
        be.setChanged();
    }

    // ---- putting fuel in ----------------------------------------------------------------------------------------

    /**
     * The tank as a place items can be put into (and nothing taken from): it takes what the airfields take as fuel into
     * the store, as many as there is room for (in the store, and up to a full tank).
     */
    static IItemHandler tank(AircraftBlockEntity be) {
        return new IItemHandler() {
            @Override
            public int getSlots() {
                return 1;
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return ItemStack.EMPTY;
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                final int each = stack.isEmpty() ? 0 : burn(stack.getItem());
                if (each <= 0) return stack;
                final int taken = (int) Math.min(Math.min(stack.getCount(), be.store.room(stack)), Math.max(0, capacity() - total(be) + each - 1) / each);
                if (taken <= 0) return stack;
                if (!simulate) be.store.addItem(stack.copyWithCount(taken));
                return stack.copyWithCount(stack.getCount() - taken);
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return ItemStack.EMPTY;
            }

            @Override
            public int getSlotLimit(int slot) {
                return 64;
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return burn(stack.getItem()) > 0;
            }
        };
    }

    /** What the pilot takes next from the racks for the tank: an item that is fuel and how many of it, or null. */
    @org.jetbrains.annotations.Nullable
    static java.util.Map.Entry<Item, Integer> next(AircraftBlockEntity be, @org.jetbrains.annotations.Nullable IItemHandler racks, int stacks) {
        final long room = capacity() - total(be);
        for (final java.util.Map.Entry<Item, Integer> stock : Cargo.contents(racks).entrySet()) {
            final int each = burn(stock.getKey());
            if (each <= 0) continue;
            final int n = (int) Math.min(Math.min(stock.getValue(), (room + each - 1) / each), (long) stacks * stock.getKey().getDefaultMaxStackSize());
            if (n > 0) return java.util.Map.entry(stock.getKey(), n);
        }
        return null;
    }

    /** How many items of fuel lie on an airfield's racks. */
    static int stock(@org.jetbrains.annotations.Nullable IItemHandler racks) {
        int sum = 0;
        for (final java.util.Map.Entry<Item, Integer> stock : Cargo.contents(racks).entrySet()) if (burn(stock.getKey()) > 0) sum += stock.getValue();
        return sum;
    }

    /** The items airfields ask their colony for when they are short of fuel. */
    static List<ItemStack> asked() {
        final List<ItemStack> all = new java.util.ArrayList<>();
        for (final Item item : Cargo.fuelItems()) all.add(new ItemStack(item));
        return all;
    }
}
