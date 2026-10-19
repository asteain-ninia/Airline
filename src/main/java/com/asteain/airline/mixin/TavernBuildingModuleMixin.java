package com.asteain.airline.mixin;

import com.asteain.airline.colony.VisitorArrival;
import com.minecolonies.core.colony.buildings.modules.TavernBuildingModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * A tavern's visitors and the air network.
 *
 * <p>Where one appears (2026-10-17, 旅客 3 first version): in a colony joined to others by the air network, at an airfield,
 * as if it had flown in, and it walks to the tavern from there.</p>
 *
 * <p>When it comes (2026-10-19, the user's rulings "空路のある村では止める" and "酒場の湧きに比べて有利にも不利にもならないように…
 * 脇タイミングだけ発着に離散化"): in such a colony the tavern does not let one appear by itself when its time comes; the
 * visitor is owed instead (as many as the tavern has room for), and the owed ones arrive with the next aircraft that lands in
 * the colony from another one, where its pilot gets off. The tavern's own rules (its room, the time between two) are kept.</p>
 */
@Mixin(value = TavernBuildingModule.class, remap = false)
public abstract class TavernBuildingModuleMixin implements VisitorArrival.Tavern {
    @Shadow
    @Final
    private List<Integer> externalCitizens;

    @Shadow
    private void spawnVisitorInternal() {
    }

    /** Visitors whose time came while the colony is joined to others by air: they come with the next aircraft. */
    @Unique
    private int airline$owed;

    @ModifyArg(method = "spawnVisitor", index = 2, at = @At(value = "INVOKE",
            target = "Lcom/minecolonies/api/colony/managers/interfaces/IVisitorManager;spawnOrCreateCivilian(Lcom/minecolonies/api/colony/ICivilianData;Lnet/minecraft/world/level/Level;Ljava/util/List;Z)Lcom/minecolonies/api/colony/ICivilianData;"))
    private List<BlockPos> airline$arriveByAir(final List<BlockPos> spawns) {
        return VisitorArrival.spawns(((TavernBuildingModule) (Object) this).getBuilding(), spawns);
    }

    @Redirect(method = "onColonyTick", at = @At(value = "INVOKE",
            target = "Lcom/minecolonies/core/colony/buildings/modules/TavernBuildingModule;spawnVisitorInternal()V"))
    private void airline$owedByAir(final TavernBuildingModule self) {
        if (VisitorArrival.byAir(self.getBuilding())) {
            airline$owed = Math.min(airline$owed + 1, airline$room());
            VisitorArrival.owed(self.getBuilding(), airline$owed);
            return;
        }
        spawnVisitorInternal();
    }

    @Inject(method = "onColonyTick", at = @At("TAIL"))
    private void airline$noLongerByAir(final com.minecolonies.api.colony.IColony colony, final CallbackInfo ci) {
        // Owed while the colony was joined to others by air, and it is not any more: they come the usual way.
        if (airline$owed > 0 && !VisitorArrival.byAir(((TavernBuildingModule) (Object) this).getBuilding())) airline$deliver();
    }

    @Inject(method = "serializeNBT", at = @At("TAIL"))
    private void airline$save(final HolderLookup.Provider provider, final CompoundTag tag, final CallbackInfo ci) {
        if (airline$owed > 0) tag.putInt("airlineOwed", airline$owed);
    }

    @Inject(method = "deserializeNBT", at = @At("TAIL"))
    private void airline$load(final HolderLookup.Provider provider, final CompoundTag tag, final CallbackInfo ci) {
        airline$owed = tag.getInt("airlineOwed");
    }

    @Unique
    private int airline$room() {
        return Math.max(0, ((TavernBuildingModule) (Object) this).getBuilding().getBuildingLevel() - externalCitizens.size());
    }

    @Override
    public int airline$owed() {
        return airline$owed;
    }

    @Override
    public void airline$setOwed(final int owed) {
        airline$owed = owed;
    }

    @Override
    public int airline$deliver() {
        final int n = Math.min(airline$owed, airline$room());
        for (int i = 0; i < n; i++) spawnVisitorInternal();
        airline$owed = 0;
        return n;
    }
}
