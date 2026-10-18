package com.asteain.airline.client;

import com.asteain.airline.Airline;
import com.minecolonies.api.client.render.modeltype.CitizenModel;
import com.minecolonies.api.client.render.modeltype.IModelType;
import com.minecolonies.api.client.render.modeltype.ModModelTypes;
import com.minecolonies.api.client.render.modeltype.SimpleModelType;
import com.minecolonies.api.client.render.modeltype.registry.IModelTypeRegistry;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * The pilot's look, on the client: a kind of citizen look named {@link Airline#PILOT_LOOK}. It has no shape of its
 * own; it borrows the models of MineColonies' plain citizen and only asks for other textures (MineColonies finds them
 * by the kind's name: {@code assets/minecolonies/textures/entity/citizen/<style>/pilot{male,female}1_<tone>.png}, shipped
 * in this mod's jar, with the "default" style as the fallback for every other style).
 *
 * <p>MineColonies' citizen renderer does not check for a kind it does not know (it would fail to draw the pilot), so
 * this must be registered on every client before a pilot comes into view. It is done from the client tick, from the
 * first tick on, as soon as MineColonies has made its own kinds (it makes them together with its citizen renderer, when
 * resources are loaded), and again whenever MineColonies makes them anew (resource reload).</p>
 *
 * <p>Client only. Only ever touched from {@link Airline}'s constructor behind a check for the client side.</p>
 */
public final class PilotLook {
    private PilotLook() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static CitizenModel<AbstractEntityCitizen> borrowed;
    private static boolean told;

    public static void hook() {
        NeoForge.EVENT_BUS.addListener(PilotLook::onTick);
        // (Not about the pilot's look: the structure pack under MineColonies' heading on the client as well, see PackPlace.)
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            if (++packLook % 100 == 0) com.asteain.airline.PackPlace.mend();
        });
    }

    private static int packLook;

    private static void onTick(ClientTickEvent.Pre event) {
        try {
            final IModelTypeRegistry registry = IModelTypeRegistry.getInstance();
            final IModelType citizen = registry == null ? null : registry.getModelType(ModModelTypes.CITIZEN_ID);
            if (citizen == null || citizen.getMaleModel() == null || citizen.getFemaleModel() == null) return;      // not made yet
            if (citizen.getMaleModel() == borrowed && registry.getModelType(Airline.PILOT_LOOK) != null) return;    // up to date
            registry.register(new SimpleModelType(Airline.PILOT_LOOK, 1, citizen.getMaleModel(), citizen.getFemaleModel()));
            borrowed = citizen.getMaleModel();
            LOGGER.debug("[Airline] the pilot's look is registered with MineColonies.");
        } catch (RuntimeException | LinkageError e) {
            if (!told) LOGGER.error("[Airline] the pilot's look could not be registered. If the game fails when a pilot comes into view, set pilotSkin = false in airline-common.toml on the server.", e);
            told = true;
        }
    }
}
