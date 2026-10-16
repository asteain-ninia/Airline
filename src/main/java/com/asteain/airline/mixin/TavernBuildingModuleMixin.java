package com.asteain.airline.mixin;

import com.asteain.airline.colony.VisitorArrival;
import com.minecolonies.core.colony.buildings.modules.TavernBuildingModule;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

/**
 * A tavern's visitor appears where MineColonies spawns it: at a gate house's spawn points, or at the tavern. In a colony
 * joined to others by the air network it arrives at an airfield instead, as if it had flown in, and walks to the tavern
 * from there (2026-10-17, the user's ruling "判断は一旦全部推奨で": 旅客 3, the first version only moves where it appears).
 */
@Mixin(value = TavernBuildingModule.class, remap = false)
public abstract class TavernBuildingModuleMixin {
    @ModifyArg(method = "spawnVisitor", index = 2, at = @At(value = "INVOKE",
            target = "Lcom/minecolonies/api/colony/managers/interfaces/IVisitorManager;spawnOrCreateCivilian(Lcom/minecolonies/api/colony/ICivilianData;Lnet/minecraft/world/level/Level;Ljava/util/List;Z)Lcom/minecolonies/api/colony/ICivilianData;"))
    private List<BlockPos> airline$arriveByAir(final List<BlockPos> spawns) {
        return VisitorArrival.spawns(((TavernBuildingModule) (Object) this).getBuilding(), spawns);
    }
}
