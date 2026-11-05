package com.asteain.airline.colony;

import com.asteain.airline.ledger.WantLedger;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The airfield's requests tab (which colony wants what, for every colony of the world): for each colony of the
 * dimension, its own first, what it wants (by the ledger: typed in, or read from the
 * requests nobody in it can meet) and how much of that is on its way. At most {@link #ITEMS} items a colony.
 */
public class WantsModule extends AbstractBuildingModule implements ITickingModule {
    public static final int ITEMS = 8;
    private int ticks;

    @Override
    public void onColonyTick(@NotNull IColony colony) {
        if (++ticks % 5 == 0) markDirty();
    }

    @Override
    public void serializeToView(@NotNull RegistryFriendlyByteBuf buf) {
        final IColony own = building.getColony();
        if (!(own.getWorld() instanceof ServerLevel level)) {
            buf.writeVarInt(0);
            return;
        }
        final WantLedger ledger = WantLedger.get(level.getServer());
        final String dimension = level.dimension().location().toString();
        final Map<Integer, Map<Item, Integer>> wants = new LinkedHashMap<>();
        for (final WantLedger.Entry entry : ledger.entries()) {
            if (!entry.key().dimension().equals(dimension)) continue;
            wants.computeIfAbsent(entry.key().colony(), c -> new LinkedHashMap<>()).merge(entry.item(), entry.count(), Integer::sum);
        }
        final List<IColony> colonies = new ArrayList<>(IColonyManager.getInstance().getColonies(level));
        colonies.sort(Comparator.comparing((IColony c) -> c != own).thenComparingDouble(c -> c.getCenter().distSqr(own.getCenter())));
        buf.writeVarInt(colonies.size());
        for (final IColony colony : colonies) {
            buf.writeUtf(colony.getName());
            buf.writeBoolean(colony == own);
            final WantLedger.Key key = WantLedger.Key.of(colony);
            final List<Map.Entry<Item, Integer>> items = new ArrayList<>(wants.getOrDefault(colony.getID(), Map.of()).entrySet());
            items.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            final int shown = Math.min(ITEMS, items.size());
            buf.writeVarInt(shown);
            for (int i = 0; i < shown; i++) {
                final Item item = items.get(i).getKey();
                buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(item));
                buf.writeVarInt(items.get(i).getValue());
                buf.writeVarInt(ledger.covered(key, item));
            }
            buf.writeVarInt(items.size() - shown);
        }
    }
}
