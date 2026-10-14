package com.asteain.airline.colony;

import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;

import java.util.List;
import java.util.function.Function;

/**
 * The airfield's worker module: MineColonies' own, minus its request resolvers.
 *
 * <p>The parent registers two crafting resolvers that are saved inside the colony's request system together with the
 * job's registry name. If this mod is removed later, MineColonies cannot read a resolver of an unknown job, and with it the
 * whole table of resolvers of that colony fails to load (seen in the "removed" check before this class existed).
 * A pilot crafts nothing, so the airfield simply offers no resolver: nothing of this mod ends up in the request system's data.</p>
 */
public class PilotWorkModule extends WorkerBuildingModule {
    public PilotWorkModule(JobEntry entry, Skill primary, Skill secondary, boolean canWorkDuringRain, Function<IBuilding, Integer> sizeLimit) {
        super(entry, primary, secondary, canWorkDuringRain, sizeLimit);
    }

    @Override
    public List<IRequestResolver<?>> createResolvers() {
        return List.of();
    }
}
