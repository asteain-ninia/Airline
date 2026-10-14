package com.asteain.airline.colony;

import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;

import static com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.IDLE;
import static com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.START_WORKING;
import static com.minecolonies.api.util.constant.Constants.TICKS_SECOND;

/** Walks to the airfield and stays there; while away with the aircraft, stays at the place the flight gave. The pilot carries no cargo. */
public class EntityAIWorkPilot extends AbstractEntityAIBasic<JobPilot, BuildingAirfield> {
    public EntityAIWorkPilot(@NotNull JobPilot job) {
        super(job);
        super.registerTargets(
                new AITarget<IAIState>(IDLE, START_WORKING, 1),
                new AITarget<IAIState>(START_WORKING, this::stayAtAirfield, TICKS_SECOND));
    }

    @Override
    public Class<BuildingAirfield> getExpectedBuildingClass() {
        return BuildingAirfield.class;
    }

    private IAIState stayAtAirfield() {
        final BlockPos post = job.getDutyPost();
        // Away from home the pilot must not try to walk back to its own airfield: that is a flight away.
        if (post != null) EntityNavigationUtils.walkToPos(worker, post, 3, false);
        else walkToBuilding();
        return START_WORKING;
    }
}
