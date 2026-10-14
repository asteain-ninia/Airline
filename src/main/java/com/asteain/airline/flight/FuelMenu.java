package com.asteain.airline.flight;

import com.asteain.airline.Airline;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The window of an aircraft's fuel store: its nine slots (fuel only) over the player's inventory, laid out like a one-row chest. */
public final class FuelMenu extends AbstractContainerMenu {
    private final Container store;

    /** On the client: an empty store of the same size (what is in it comes from the server). */
    public FuelMenu(int id, Inventory inventory) {
        this(id, inventory, new FuelStore(null));
    }

    public FuelMenu(int id, Inventory inventory, Container store) {
        super(Airline.FUEL_MENU.get(), id);
        checkContainerSize(store, FuelStore.SLOTS);
        this.store = store;
        store.startOpen(inventory.player);
        for (int i = 0; i < FuelStore.SLOTS; i++) {
            addSlot(new Slot(store, i, 8 + i * 18, 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return store.canPlaceItem(getContainerSlot(), stack);
                }
            });
        }
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 49 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 107));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        final Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        final ItemStack stack = slot.getItem(), before = stack.copy();
        if (index < FuelStore.SLOTS) {
            if (!moveItemStackTo(stack, FuelStore.SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 0, FuelStore.SLOTS, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    @Override
    public boolean stillValid(Player player) {
        return store.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        store.stopOpen(player);
    }
}
