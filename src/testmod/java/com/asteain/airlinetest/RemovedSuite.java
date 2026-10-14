package com.asteain.airlinetest;

import com.asteain.airline.Airline;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.IMinecoloniesAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The world the airfield suite left, started WITHOUT the mod: does it open, and is the colony still usable?
 * This class must not touch any class of the mod (only the compile-time constant of its id).
 */
final class RemovedSuite {
    private RemovedSuite() {}

    static Script build(MinecraftServer server) throws Exception {
        Script s = new Script();
        s.label = "removed";
        ServerLevel level = server.overworld();
        Map<String, String> state = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("airline-state.txt"))) {
            int eq = line.indexOf('=');
            if (eq > 0) state.put(line.substring(0, eq), line.substring(eq + 1));
        }
        int colonyId = Integer.parseInt(state.get("colony"));
        String[] p = state.get("airfield").split(",");
        BlockPos airfieldPos = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        int pilotId = Integer.parseInt(state.get("pilot"));
        IColony[] colony = new IColony[1];

        s.step("mods", () -> {
            // In this development run the mod's resource folder is still on the class path (its data files fail to load and are
            // reported in the log); what matters is that its code never ran: nothing of it is registered.
            ResourceLocation hut = ResourceLocation.fromNamespaceAndPath(Airline.MOD_ID, Airline.HUT_NAME);
            s.check(!BuiltInRegistries.BLOCK.containsKey(hut) && ModList.get().isLoaded("minecolonies"), "the server runs with MineColonies and without the mod's code (its hut block is not registered)");
            s.check(!IMinecoloniesAPI.getInstance().getBuildingRegistry().containsKey(ResourceLocation.fromNamespaceAndPath(Airline.MOD_ID, Airline.BUILDING_NAME))
                    && !IMinecoloniesAPI.getInstance().getJobRegistry().containsKey(ResourceLocation.fromNamespaceAndPath(Airline.MOD_ID, Airline.JOB_NAME)), "MineColonies knows neither the airfield nor the pilot");
        });
        s.until("colony is loaded", () -> {}, () -> (colony[0] = IColonyManager.getInstance().getColonyByDimension(colonyId, level.dimension())) != null, 200);
        int[] requestsAtLoad = new int[1];
        s.step("the request the airfield left open", () -> {
            requestsAtLoad[0] = knownRequests(colony[0]);
            s.note("OBS requests in the colony's request system right after the load (the airfield had one open, plus its delivery): " + requestsAtLoad[0]);
            // The hard case: the orphaned request's delivery fails, so MineColonies hands the request (whose building is gone) to the resolvers again.
            IRequestManager requests = colony[0].getRequestManager();
            var all = new java.util.ArrayList<>(((com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager) requests).getRequestIdentitiesDataStore().getIdentities().values());
            int reassigned = 0;
            for (var request : all) {
                if (!(request.getRequest() instanceof Stack) || !request.hasChildren()) continue;
                boolean buildingGone = colony[0].getServerBuildingManager().getBuilding(request.getRequester().getLocation().getInDimensionLocation()) == null;
                IToken<?> child = request.getChildren().iterator().next();
                requests.updateRequestState(child, RequestState.FAILED);
                reassigned++;
                var after = requests.getRequestForToken(request.getId());
                s.note("OBS orphaned request (requester's building gone=" + buildingGone + ") after its delivery failed: "
                        + (after == null ? "removed" : "state " + after.getState() + ", held by " + requests.getResolverForRequest(request.getId()).getClass().getSimpleName()));
            }
            s.check(reassigned == 1, "the export request left open by the vanished airfield was handed out again without an exception (" + reassigned + ")");
        });
        s.settle("let the world run", 600);
        s.step("without the mod", () -> {
            s.check(true, "the world opened and colony " + colonyId + " was loaded");
            level.getChunk(airfieldPos);
            IBuilding airfield = colony[0].getServerBuildingManager().getBuilding(airfieldPos);
            s.note("OBS building at the airfield's position: " + (airfield == null ? "none (dropped as an unknown building type)" : airfield.getClass().getSimpleName())
                    + "; block there: " + level.getBlockState(airfieldPos));
            s.check(airfield == null, "the airfield is gone from the colony's buildings");
            s.check(colony[0].getServerBuildingManager().getTownHall() != null, "the town hall is still there");
            s.check(colony[0].getServerBuildingManager().getWareHouses().size() == 1, "the warehouse is still there");
            IWareHouse warehouse = colony[0].getServerBuildingManager().getWareHouses().get(0);
            s.note("OBS warehouse stock of iron ingots without the mod: " + Fixture.count(warehouse, Items.IRON_INGOT) + ", buildings in the colony: " + colony[0].getServerBuildingManager().getBuildings().size());
            ICitizenData pilot = colony[0].getCitizenManager().getCivilian(pilotId);
            s.check(pilot != null, "the former pilot is still a citizen");
            s.note("OBS the former pilot's job: " + (pilot.getJob() == null ? "none" : pilot.getJob().getClass().getSimpleName()) + ", work building: " + pilot.getWorkBuilding());
            s.check(pilot.getJob() == null, "the former pilot has no job");
            IRequestManager requests = colony[0].getRequestManager();
            int waiting = requests.getPlayerResolver().getAllAssignedRequests().size() + requests.getRetryingRequestResolver().getAllAssignedRequests().size();
            s.note("OBS requests waiting for the player or a retry: " + waiting);
            IToken<?> probe = colony[0].getServerBuildingManager().getTownHall().createRequest(new Stack(new ItemStack(Items.IRON_INGOT), 8, 8), true);
            s.check(requests.getRequestForToken(probe) != null, "the request system still takes a new request (held by " + requests.getResolverForRequest(probe).getClass().getSimpleName() + ")");
            requests.updateRequestState(probe, RequestState.CANCELLED);
            for (int i = 0; i < 4000; i++) requests.tick();
            s.check(true, "the request system ticks 4000 times (through all retries) without an exception");
            s.note("OBS requests in the colony's request system: " + requestsAtLoad[0] + " at load -> " + knownRequests(colony[0]) + " after 600 world ticks and 4000 request-system ticks; warehouse stock of iron ingots now "
                    + Fixture.count(warehouse, Items.IRON_INGOT));
        });
        return s;
    }

    private static int knownRequests(IColony colony) {
        return ((com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager) colony.getRequestManager()).getRequestIdentitiesDataStore().getIdentities().size();
    }
}
