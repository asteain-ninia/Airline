package com.asteain.airline.colony;

import com.asteain.airline.Airline;
import com.asteain.airline.route.RouteRules;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.jobs.AbstractJob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The pilot. At home the pilot stays at the airfield. A flight sends the pilot to the aircraft's seat and off again
 * (ColonyPatchServer's boarding does the walking and sitting); while the aircraft waits at another airfield the pilot
 * stays near it, at the "duty post". Cargo handling is the couriers' work.
 */
public class JobPilot extends AbstractJob<EntityAIWorkPilot, JobPilot> {
    private static final String TAG_DUTY_POST = "dutyPost";
    /** Where to stay instead of the home airfield (a place on another airfield's pad); null when at home. */
    @Nullable private BlockPos dutyPost;

    public JobPilot(ICitizenData citizen) {
        super(citizen);
    }

    @Override
    public EntityAIWorkPilot generateAI() {
        return new EntityAIWorkPilot(this);
    }

    /**
     * The pilot's own look (see client.PilotLook, which makes it known to MineColonies on every client). With the setting
     * switched off the pilot looks like any citizen without a special look.
     */
    @Override
    public ResourceLocation getModel() {
        return RouteRules.pilotSkin() ? Airline.PILOT_LOOK : super.getModel();
    }

    @Nullable
    public BlockPos getDutyPost() {
        return dutyPost;
    }

    public void setDutyPost(@Nullable BlockPos post) {
        dutyPost = post == null ? null : post.immutable();
    }

    @Override
    public @NotNull CompoundTag serializeNBT(HolderLookup.@NotNull Provider provider) {
        final CompoundTag tag = super.serializeNBT(provider);
        if (dutyPost != null) tag.putLong(TAG_DUTY_POST, dutyPost.asLong());
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.@NotNull Provider provider, CompoundTag tag) {
        super.deserializeNBT(provider, tag);
        dutyPost = tag.contains(TAG_DUTY_POST) ? BlockPos.of(tag.getLong(TAG_DUTY_POST)) : null;
    }
}
