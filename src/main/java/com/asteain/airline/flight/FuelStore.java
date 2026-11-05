package com.asteain.airline.flight;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The aircraft's fuel store (the fuel is kept in the inventory of the aircraft's own block): nine slots that take only
 * what airfields take as fuel. The engines burn it one item at a time ({@link Fuel});
 * the pilot fills it from the airfield's racks; a player opens it by using the block with an empty hand.
 */
public final class FuelStore extends SimpleContainer {
    public static final int SLOTS = 9;
    @Nullable
    private final AircraftBlockEntity owner;

    /** {@code owner} null: the empty copy a client shows until the server says what is in it. */
    public FuelStore(@Nullable AircraftBlockEntity owner) {
        super(SLOTS);
        this.owner = owner;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return Fuel.burn(stack.getItem()) > 0;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (owner != null) owner.setChanged();
    }

    /**
     * Open as long as the block is there. (No distance is asked for: on a ship the block stands in Sable's far-away
     * plot, whatever way the player reached it.)
     */
    @Override
    public boolean stillValid(Player player) {
        return owner == null || !owner.isRemoved();
    }

    /** How many of this stack the store could still take (slots that hold the same item, then empty ones). */
    int room(ItemStack stack) {
        int room = 0;
        for (int slot = 0; slot < getContainerSize(); slot++) {
            final ItemStack held = getItem(slot);
            if (held.isEmpty()) room += Math.min(getMaxStackSize(), stack.getMaxStackSize());
            else if (ItemStack.isSameItemSameComponents(held, stack)) room += Math.max(0, Math.min(getMaxStackSize(), held.getMaxStackSize()) - held.getCount());
        }
        return room;
    }
}
