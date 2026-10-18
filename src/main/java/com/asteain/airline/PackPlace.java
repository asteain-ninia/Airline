package com.asteain.airline;

import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.List;

/**
 * The mod's structure pack under MineColonies' heading in Structurize's window of packs (2026-10-18, the user's
 * "パック選択画面にMinecoloniesとLocalと同格としてAirlineが入ってしまっています"). Structurize heads the packs in that
 * window by their owner, and gives a pack in a mod's jar the name of the folder it lies in ({@code blueprints/airline/}):
 * a heading of its own, beside MineColonies and Local. The pack is found once more under MineColonies' name (the same
 * pack: the same files, the same name; only the heading changes). Looked at now and then on both sides, since a client
 * that joins a server gets its packs anew.
 */
public final class PackPlace {
    private PackPlace() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    /** The heading: MineColonies' own packs lie in its jar's folder {@code blueprints/minecolonies/}. */
    public static final String OWNER = "minecolonies";
    private static boolean told;

    public static void mend() {
        try {
            if (!StructurePacks.hasPack(Airline.PACK_NAME)) return;
            final StructurePackMeta pack = StructurePacks.getStructurePack(Airline.PACK_NAME);
            if (pack == null || OWNER.equals(pack.getOwner()) || pack.getPath() == null) return;
            final List<String> loaded = ModList.get().getMods().stream().map(mod -> mod.getModId()).toList();
            StructurePacks.discoverPackAtPath(pack.getPath(), pack.isImmutable(), loaded, false, OWNER);
        } catch (RuntimeException | LinkageError e) {
            if (!told) LOGGER.warn("[Airline] the structure pack could not be put under MineColonies' heading; it keeps its own.", e);
            told = true;
        }
    }
}
