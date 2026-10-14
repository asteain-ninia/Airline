package com.asteain.airlinetest;

import com.asteain.airline.Airline;
import com.asteain.airline.block.AirfieldBlockEntity;
import com.asteain.airline.colony.BuildingAirfield;
import com.asteain.airline.colony.ExportStockModule;
import com.asteain.airline.colony.JobPilot;
import com.asteain.airline.ledger.WantLedger;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** A second server start on the world the airfield suite left: what survived the save and the load. */
final class ReloadSuite {
    private ReloadSuite() {}

    static Script build(MinecraftServer server) throws Exception {
        Script s = new Script();
        s.label = "reload";
        ServerLevel level = server.overworld();
        Map<String, String> state = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(AirfieldSuite.STATE_FILE))) {
            int eq = line.indexOf('=');
            if (eq > 0) state.put(line.substring(0, eq), line.substring(eq + 1));
        }
        int colonyId = Integer.parseInt(state.get("colony"));
        BlockPos airfieldPos = pos(state.get("airfield"));
        BlockPos pastedPos = pos(state.get("pasted"));
        int pilotId = Integer.parseInt(state.get("pilot"));
        int onRacks = Integer.parseInt(state.get("onRacks"));
        int wanted = Integer.parseInt(state.get("wanted"));
        IColony[] colony = new IColony[1];

        s.until("colony is loaded", () -> {}, () -> (colony[0] = IColonyManager.getInstance().getColonyByDimension(colonyId, level.dimension())) != null, 200);
        s.step("the open request right after the load", () -> {
            IBuilding building = colony[0].getServerBuildingManager().getBuilding(airfieldPos);
            s.check(building instanceof BuildingAirfield, "the airfield is a building of colony " + colonyId + " right after the load");
            var open = AirfieldSuite.exportRequests((com.minecolonies.core.colony.buildings.AbstractBuilding) building, colony[0]);
            s.check(open.size() == 1 && ((com.minecolonies.api.colony.requestsystem.requestable.Stack) open.get(0).getRequest()).getCount() == 20,
                    "the export request that was open when the world was saved is still open (" + open.size() + ")");
            s.check(colony[0].getRequestManager().getResolverForRequest(open.get(0).getId()) instanceof com.minecolonies.core.colony.requestsystem.resolvers.core.AbstractWarehouseRequestResolver,
                    "and still held by the warehouse");
        });
        s.settle("let block entities link to their buildings", 40);
        s.step("after the reload", () -> {
            level.getChunk(airfieldPos);
            IBuilding building = colony[0].getServerBuildingManager().getBuilding(airfieldPos);
            s.check(building instanceof BuildingAirfield, "the airfield is still a building of colony " + colonyId + ": " + (building == null ? null : building.getClass().getSimpleName()));
            s.check(building.getBuildingType() == Airline.AIRFIELD.get() && building.getBuildingLevel() == 1, "type and level survived: " + building.getBuildingType().getRegistryName() + " level " + building.getBuildingLevel());
            s.check(level.getBlockEntity(airfieldPos) instanceof AirfieldBlockEntity, "the hut's block entity was read back as the mod's type");
            s.check(building.getTileEntity() != null, "the building found its block entity again");
            s.check(Airline.PACK_NAME.equals(building.getStructurePack()) && Airline.BLUEPRINT_PATH.equals(building.getBlueprintPath()),
                    "the hand-placed airfield still names the shipped blueprint, pack and path together: " + building.getStructurePack() + " / " + building.getBlueprintPath());
            ExportStockModule table = building.getFirstModuleOccurance(ExportStockModule.class);
            s.check(Integer.valueOf(1).equals(table.table().get(new ItemStorage(new ItemStack(Items.IRON_INGOT)))), "the export table kept its row (iron ingot, keep 1 stack): " + table.table().size() + " row(s)");
            s.check(building.getContainers().contains(airfieldPos.offset(-2, 0, 0)) && building.getContainers().contains(airfieldPos.offset(2, 0, 0)), "the export racks are still the building's containers");
            int racks = InventoryUtils.getItemCountInItemHandler(building.getItemHandlerCap(), stack -> stack.is(Items.IRON_INGOT));
            s.check(racks == onRacks || racks == onRacks + 20, "the export racks still hold " + onRacks + " iron ingots, or the 20 on their way arrived meanwhile (" + racks + ")");
            ICitizenData pilot = colony[0].getCitizenManager().getCivilian(pilotId);
            s.check(pilot != null && pilot.getJob() instanceof JobPilot, "citizen " + pilotId + " is still a pilot: " + (pilot == null || pilot.getJob() == null ? null : pilot.getJob().getClass().getSimpleName()));
            s.check(pilot.getWorkBuilding() == building, "the pilot still works at the airfield");
            IBuilding pasted = colony[0].getServerBuildingManager().getBuilding(pastedPos);
            // (The first run placed the blueprints of levels 2 to 5 over it in turn; it was level 5, with 20 racks, when the server stopped.)
            int racksKept = 0;
            if (pasted != null) for (BlockPos spot : pasted.getContainers()) if (level.getBlockState(spot).getBlock() == com.minecolonies.api.blocks.ModBlocks.blockRack) racksKept++;
            s.check(pasted instanceof BuildingAirfield field && pasted.getBuildingLevel() == 5 && racksKept == 20 && field.pads().size() == 2,
                    "the airfield pasted from the blueprint and upgraded to level 5 survived too: level " + (pasted == null ? "?" : pasted.getBuildingLevel()) + ", racks " + racksKept);
            s.note("OBS pasted airfield after reload: pack=" + pasted.getStructurePack() + " path=" + pasted.getBlueprintPath() + " containers=" + pasted.getContainers().size());
            WantLedger ledger = WantLedger.get(server);
            WantLedger.Key other = new WantLedger.Key(level.dimension().location().toString(), AirfieldSuite.OTHER_COLONY);
            s.check(ledger.entries().size() == 1 && ledger.wanted(other, Items.IRON_INGOT) == wanted, "the ledger kept its entry (another colony wants " + wanted + " iron ingots): " + ledger.entries());
            // the export logic still runs on the loaded data
            table.onColonyTick(colony[0]);
            s.note("OBS open export requests after one tick on the reloaded colony: " + AirfieldSuite.exportRequests((com.minecolonies.core.colony.buildings.AbstractBuilding) building, colony[0]).size());
        });
        return s;
    }

    private static BlockPos pos(String text) {
        String[] p = text.split(",");
        return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }
}
