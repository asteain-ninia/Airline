package com.asteain.airlinetest;

import com.asteain.airline.Airline;
import com.asteain.airline.block.AirfieldBlockEntity;
import com.asteain.airline.colony.BuildingAirfield;
import com.asteain.airline.colony.ExportStockModule;
import com.asteain.airline.colony.ExportStockModuleView;
import com.asteain.airline.colony.JobPilot;
import com.asteain.airline.ledger.WantLedger;
import com.asteain.airline.route.RouteNetwork;
import com.asteain.airline.route.RouteRules;
import com.google.common.reflect.TypeToken;
import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.management.Manager;
import com.ldtteam.structurize.operations.PlaceStructureOperation;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Pickup;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.player.IPlayerRequestResolver;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.sounds.EventType;
import com.minecolonies.api.sounds.ModSoundEvents;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.SoundUtils;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.MinimumStockModule;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.requestsystem.resolvers.core.AbstractWarehouseRequestResolver;
import com.minecolonies.api.tileentities.MinecoloniesTileEntities;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.core.tileentities.TileEntityRack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Stage 3 on a dedicated server without players: the airfield and the pilot register and work, the export table asks the
 * warehouse for surplus that another colony wants (and for nothing else), and the shipped blueprint is read and pasted.
 * The colony is never "active" without a player, so the colony tick of the export table is called by hand.
 */
final class AirfieldSuite {
    private AirfieldSuite() {}

    static final String STATE_FILE = "airline-state.txt";
    /** A colony number that does not exist: stands for "some other colony" in the ledger. */
    static final int OTHER_COLONY = 9999;

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "airfield";
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos origin = new BlockPos(spawn.getX() + 2048 + 256 * Fixture.existing(), 100, spawn.getZ());
        BlockPos airfieldPos = origin.offset(-8, 0, 0);
        BlockPos warehousePos = origin.offset(8, 0, 0);
        BlockPos courierHutPos = origin.offset(8, 0, -8);
        BlockPos pastePos = origin.offset(0, 0, 50);      // the blueprint reaches 22 blocks north of its hut
        boolean withCourier = Boolean.parseBoolean(System.getProperty("airlinetest.courier", "true"));

        IColony[] colony = new IColony[1];
        IBuilding[] hall = new IBuilding[1];
        AbstractBuilding[] airfield = new AbstractBuilding[1];
        AbstractBuilding[] warehouse = new AbstractBuilding[1];
        TileEntityRack[] exportRack = new TileEntityRack[2];
        TileEntityRack[] stockRack = new TileEntityRack[2];
        ExportStockModule[] table = new ExportStockModule[1];
        ICitizenData[] pilot = new ICitizenData[1];
        ICitizenData[] courier = new ICitizenData[1];
        Mob[] pilotEntity = new Mob[1];
        double[] pilotStartDistance = new double[1];
        IToken<?>[] token = new IToken<?>[2];
        CompletableFuture<?>[] blueprint = new CompletableFuture<?>[1];
        WantLedger.Key other = new WantLedger.Key(level.dimension().location().toString(), OTHER_COLONY);
        ItemStack iron = new ItemStack(Items.IRON_INGOT);

        s.step("mods", () -> {
            for (String id : new String[]{"minecolonies", Airline.MOD_ID, "airlinetest"}) s.check(ModList.get().isLoaded(id), "mod loaded: " + id);
            s.note("OBS world spawn=" + spawn + " origin=" + origin + " colonies before=" + Fixture.existing());
        });
        s.until("structure pack is discovered", () -> {}, () -> StructurePacks.hasPack(Airline.PACK_NAME), 1200);
        s.until("the pack under MineColonies' heading", () -> {}, () -> com.asteain.airline.PackPlace.OWNER.equals(StructurePacks.getStructurePack(Airline.PACK_NAME).getOwner()), 400);
        s.step("the pack's heading", () -> s.soft("pack / in Structurize's window of packs the mod's pack stands under MineColonies' heading, not one of its own; its blueprints are still found",
                com.asteain.airline.PackPlace.OWNER.equals(StructurePacks.getStructurePack(Airline.PACK_NAME).getOwner()) && StructurePacks.getStructurePack(Airline.PACK_NAME).getPath() != null,
                StructurePacks.getStructurePack(Airline.PACK_NAME).getOwner()));
        s.step("registries", () -> {
            s.check(StructurePacks.hasPack(Airline.PACK_NAME), "structure pack '" + Airline.PACK_NAME + "' is registered from the mod's resources");
            s.check(IMinecoloniesAPI.getInstance().getBuildingRegistry().containsKey(Airline.id(Airline.BUILDING_NAME)), "building registry has " + Airline.id(Airline.BUILDING_NAME));
            s.check(IMinecoloniesAPI.getInstance().getJobRegistry().containsKey(Airline.id(Airline.JOB_NAME)), "job registry has " + Airline.id(Airline.JOB_NAME));
            s.check(BuiltInRegistries.BLOCK.containsKey(Airline.id(Airline.HUT_NAME)) && BuiltInRegistries.ITEM.containsKey(Airline.id(Airline.HUT_NAME)), "hut block and item are registered under the mod's id");
            s.check(BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(Airline.id(Airline.BUILDING_NAME)), "the mod's own block entity type is registered");
            s.check(Airline.AIRFIELD_BLOCK_ENTITY.get().isValid(Airline.AIRFIELD_HUT.get().defaultBlockState()), "the hut is a valid block for the mod's block entity type");
            s.check(ModSoundEvents.CITIZEN_SOUND_EVENTS.containsKey(Airline.JOB_NAME), "the pilot has an entry in MineColonies' citizen voice table");
            // The pilot's look: the job names it, and the textures MineColonies will ask for by that name are in this mod's jar
            // (under MineColonies' own namespace). The client side (registering the look) cannot be checked on a server.
            List<String> noTexture = new ArrayList<>();
            for (String sex : new String[] {"male", "female"}) for (String tone : new String[] {"_a", "_b", "_d", "_w"}) {
                String file = Airline.PILOT_LOOK.getPath() + sex + "1" + tone + ".png";
                if (Airline.class.getResource("/assets/minecolonies/textures/entity/citizen/default/" + file) == null) noTexture.add(file);
                for (String style : new String[] {"default", "eastasian", "hellenic", "medieval", "modern", "nether", "nordic", "undead"})
                    if (Airline.class.getResource("/assets/minecolonies/textures/entity_icon/citizen/" + style + "/" + file) == null) noTexture.add(style + "/" + file);
            }
            s.check(noTexture.isEmpty(), "the pilot's 8 skins and their icons for every colony style are in the jar (missing: " + noTexture + ")");
            s.check(Airline.AIRFIELD.get().getTranslationKey().equals("com." + Airline.MOD_ID + ".building." + Airline.BUILDING_NAME), "building translation key uses the mod's id: " + Airline.AIRFIELD.get().getTranslationKey());
            Language lang = Language.getInstance();
            String[] keys = {Airline.AIRFIELD.get().getTranslationKey(), Airline.AIRFIELD.get().getTranslationKey() + ".desc", Airline.PILOT.get().getTranslationKey(),
                    Airline.AIRFIELD_HUT.get().getDescriptionId(), "block.minecolonies." + Airline.HUT_NAME, ExportStockModuleView.TAB_KEY,
                    "com." + Airline.MOD_ID + ".gui.airfield.timetable", "com." + Airline.MOD_ID + ".gui.timetable.departure", "com." + Airline.MOD_ID + ".gui.timetable.arrival",
                    "message." + Airline.MOD_ID + ".buoy.placed", "message." + Airline.MOD_ID + ".buoy.too_far", "message." + Airline.MOD_ID + ".link.made"};
            List<String> missing = new ArrayList<>();
            for (String key : keys) if (!lang.has(key)) missing.add(key);
            s.soft("translations", missing.isEmpty(), "keys looked up in the server's language table (en_us): missing=" + missing);
        });
        s.step("platform+colony", () -> {
            level.setDayTime(1000);
            server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
            Fixture.platform(level, origin, 24, 24);
            Fixture.platform(level, pastePos.offset(1, 0, -10), 12, 16);
            colony[0] = Fixture.create(level, origin, "Airline test " + Fixture.existing());
            hall[0] = Fixture.townHall(colony[0], origin);
            s.check(colony[0].getServerBuildingManager().getTownHall() != null, "colony " + colony[0].getID() + " has a town hall");
        });
        s.step("place the airfield hut", () -> {
            airfield[0] = Fixture.placeHut(level, colony[0], Airline.AIRFIELD_HUT.get(), airfieldPos);
            s.check(level.getBlockEntity(airfieldPos) instanceof AirfieldBlockEntity, "placing the hut creates the mod's block entity without an exception");
            // Is the trap real? MineColonies' own building block entity type with this hut's block state.
            BlockState hutState = level.getBlockState(airfieldPos);
            String own;
            try {
                new TileEntityColonyBuilding(MinecoloniesTileEntities.BUILDING.get(), airfieldPos, hutState);
                own = "can be created";
            } catch (RuntimeException e) {
                own = "throws " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            s.note("OBS trap check: MineColonies' own block entity type accepts the hut=" + MinecoloniesTileEntities.BUILDING.get().isValid(hutState) + "; creating it for the hut " + own);
            s.check(airfield[0] instanceof BuildingAirfield, "the hut joins the colony as " + airfield[0].getClass().getSimpleName());
            s.check(airfield[0].getBuildingType() == Airline.AIRFIELD.get(), "its building type is " + airfield[0].getBuildingType().getRegistryName());
            s.check(Airline.BLUEPRINT_PATH.equals(airfield[0].getBlueprintPath()) && Airline.PACK_NAME.equals(airfield[0].getStructurePack()),
                    "a hand-placed hut points at the shipped blueprint (pack=" + airfield[0].getStructurePack() + ", path=" + airfield[0].getBlueprintPath() + "), not at null1.blueprint");
            airfield[0].setBuildingLevel(1);
            airfield[0].setCorners(airfieldPos.offset(-3, 0, -3), airfieldPos.offset(3, 2, 3));
            s.check(airfield[0].getBuildingLevel() == 1 && airfield[0].getMaxBuildingLevel() == 5, "the airfield is level 1 of 5");
            exportRack[0] = Fixture.rack(level, airfield[0], airfieldPos.offset(-2, 0, 0));
            exportRack[1] = Fixture.rack(level, airfield[0], airfieldPos.offset(2, 0, 0));
            s.check(airfield[0].getContainers().contains(airfieldPos.offset(-2, 0, 0)) && airfield[0].getContainers().contains(airfieldPos.offset(2, 0, 0)), "two racks are registered as the airfield's export store");
            table[0] = airfield[0].getFirstModuleOccurance(ExportStockModule.class);
            s.check(airfield[0].getFirstModuleOccurance(MinimumStockModule.class) == table[0], "MineColonies' minimum stock messages will reach the export table (looked up by the parent class)");
            s.check(airfield[0].hasModule(WorkerBuildingModule.class), "the airfield has a worker module");
            s.check(airfield[0].getResolvers().isEmpty(), "the airfield offers no request resolver (so nothing naming the pilot's job is saved in the request system)");
        });
        s.step("warehouse with stock", () -> {
            warehouse[0] = Fixture.placeHut(level, colony[0], ModBlocks.blockHutWareHouse, warehousePos);
            warehouse[0].setBuildingLevel(1);
            warehouse[0].setCorners(warehousePos.offset(-3, 0, -3), warehousePos.offset(3, 2, 3));
            stockRack[0] = Fixture.rack(level, warehouse[0], warehousePos.offset(2, 0, 0));
            stockRack[1] = Fixture.rack(level, warehouse[0], warehousePos.offset(2, 0, 2));
            Fixture.stock(stockRack[0], Items.IRON_INGOT, 128);
            Fixture.stock(stockRack[1], Items.IRON_INGOT, 64);
            s.check(colony[0].getServerBuildingManager().getWareHouses().size() == 1, "the colony has one warehouse");
            s.check(Fixture.count(warehouse[0], Items.IRON_INGOT) == 192, "the warehouse holds 192 iron ingots (3 stacks)");
        });
        s.step("spawn the pilot", () -> pilot[0] = Fixture.spawn(colony[0], level, airfieldPos.offset(0, 0, 14)));
        s.until("pilot entity", () -> {}, () -> (pilotEntity[0] = Fixture.entity(pilot[0])) != null, 200);
        s.step("hire the pilot", () -> {
            WorkerBuildingModule work = airfield[0].getFirstModuleOccurance(WorkerBuildingModule.class);
            s.check(work.getJobEntry() == Airline.PILOT.get(), "the worker module hires " + work.getJobEntry().getKey());
            s.check(work.assignCitizen(pilot[0]), "a citizen is assigned to the airfield");
            s.check(pilot[0].getJob() instanceof JobPilot, "the citizen's job is " + (pilot[0].getJob() == null ? null : pilot[0].getJob().getClass().getSimpleName()));
            s.check(Airline.PILOT_LOOK.equals(pilot[0].getJob().getModel()), "the pilot's job names the pilot's own look: " + pilot[0].getJob().getModel());
            s.check(pilot[0].getWorkBuilding() == airfield[0], "the pilot's work building is the airfield");
            pilotEntity[0].setPos(airfieldPos.getX() + 0.5, airfieldPos.getY(), airfieldPos.getZ() + 14.5);
            pilotStartDistance[0] = Math.sqrt(pilotEntity[0].distanceToSqr(airfieldPos.getX() + 0.5, airfieldPos.getY(), airfieldPos.getZ() + 0.5));
            for (EventType type : EventType.values()) SoundUtils.playSoundAtCitizenWith(level, pilotEntity[0].blockPosition(), type, pilot[0]);
            s.check(true, "all " + EventType.values().length + " citizen voice events play for a pilot without an exception");
            // Is the trap real? Take the pilot's entry out of the voice table for one call.
            var voices = ModSoundEvents.CITIZEN_SOUND_EVENTS.remove(Airline.JOB_NAME);
            String without;
            try {
                SoundUtils.playSoundAtCitizenWith(level, pilotEntity[0].blockPosition(), EventType.DANGER, pilot[0]);
                without = "does not throw";
            } catch (RuntimeException e) {
                without = "throws " + e.getClass().getSimpleName();
            } finally {
                ModSoundEvents.CITIZEN_SOUND_EVENTS.put(Airline.JOB_NAME, voices);
            }
            s.note("OBS trap check: without the pilot's entry in the voice table the same call " + without);
        });

        // ---- export: requests only ----
        s.step("no want, no request", () -> {
            table[0].addMinimumStock(iron.copy(), 1);
            s.check(table[0].table().size() == 1 && table[0].isStocked(iron), "the export table has a row: iron ingot, keep 1 stack");
            WantLedger.get(server).clear(null);
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).isEmpty(), "nobody wants iron: no request although the warehouse has surplus");
        });
        s.step("a want where no line leads = no request", () -> {
            // The "other colony" of these checks has no airfield, so no line leads to it.
            WantLedger.get(server).set(other, Items.IRON_INGOT, 100);
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).isEmpty(), "another colony wants 100 iron, but this airfield has no way to it: nothing is collected for it");
            // From here on that colony stands for one that can be flown to.
            com.asteain.airline.colony.ExportStockModule.reachAllForChecks(true);
        });
        s.step("want + surplus = one request held by the warehouse", () -> {
            WantLedger.get(server).set(other, Items.IRON_INGOT, 100);
            s.check(WantLedger.get(server).wantedByOthers(WantLedger.Key.of(colony[0]), Items.IRON_INGOT) == 100, "ledger: another colony wants 100 iron ingots");
            table[0].onColonyTick(colony[0]);
            List<IRequest<?>> open = exportRequests(airfield[0], colony[0]);
            s.check(open.size() == 1, "one request is open (" + open.size() + ")");
            Stack asked = (Stack) open.get(0).getRequest();
            s.check(asked.getCount() == 64, "it asks for 64 = min(want 100, surplus 128, one stack): " + asked.getCount());
            s.check(!asked.canBeResolvedByBuilding(), "it is marked 'not from the airfield's own stock'");
            IRequestResolver<?> resolver = colony[0].getRequestManager().getResolverForRequest(open.get(0).getId());
            s.check(resolver instanceof AbstractWarehouseRequestResolver, "it is assigned to the warehouse: " + resolver.getClass().getSimpleName());
            token[0] = open.get(0).getId();
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).size() == 1 && exportRequests(airfield[0], colony[0]).get(0).getId().equals(token[0]), "a second tick does not add another request for the same item");
        });
        s.step("want removed = request withdrawn", () -> {
            WantLedger.get(server).set(other, Items.IRON_INGOT, 0);
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).isEmpty(), "the open request is cancelled when the want disappears");
            s.check(gone(colony[0], token[0]), "the cancelled request left the request system");
        });
        s.step("no surplus = no request", () -> {
            WantLedger.get(server).set(other, Items.IRON_INGOT, 100);
            table[0].addMinimumStock(iron.copy(), 3);
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).isEmpty(), "keep 3 stacks of 3: nothing to spare, no request");
            table[0].addMinimumStock(iron.copy(), 1);
        });
        s.step("second batch with stock already on the export racks", () -> {
            Fixture.stock(exportRack[0], Items.IRON_INGOT, 64);
            s.check(InventoryUtils.getItemCountInItemHandler(airfield[0].getItemHandlerCap(), stack -> stack.is(Items.IRON_INGOT)) == 64, "64 iron ingots already sit on the export racks");
            table[0].onColonyTick(colony[0]);
            List<IRequest<?>> open = exportRequests(airfield[0], colony[0]);
            s.check(open.size() == 1, "a request is open although the airfield holds the item (" + open.size() + ")");
            s.check(((Stack) open.get(0).getRequest()).getCount() == 36, "it asks only for the missing 36 of 100: " + ((Stack) open.get(0).getRequest()).getCount());
            IRequestResolver<?> resolver = colony[0].getRequestManager().getResolverForRequest(open.get(0).getId());
            s.check(resolver instanceof AbstractWarehouseRequestResolver, "it is assigned to the warehouse, not fulfilled from the airfield's own stock: " + resolver.getClass().getSimpleName());
            s.check(open.get(0).getState() != RequestState.COMPLETED && open.get(0).getState() != RequestState.RESOLVED, "it is still waiting for delivery: " + open.get(0).getState());
            token[0] = open.get(0).getId();
        });
        s.step("never more than wanted", () -> {
            WantLedger.get(server).set(other, Items.IRON_INGOT, 64);
            table[0].onColonyTick(colony[0]);
            // the open 36 stays (one request per item); withdraw it by hand and tick again: 64 wanted, 64 on the racks
            colony[0].getRequestManager().updateRequestState(token[0], RequestState.CANCELLED);
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).isEmpty(), "want 64, 64 on the racks: nothing more is requested");
            WantLedger.get(server).set(other, Items.IRON_INGOT, 100);
        });
        s.step("the colony's own shortage blocks the export", () -> {
            token[1] = hall[0].createRequest(new Stack(iron.copy(), 500, 500), true);
            IRequestResolver<?> holder = colony[0].getRequestManager().getResolverForRequest(token[1]);
            s.note("OBS the town hall's request for 500 iron is held by " + holder.getClass().getSimpleName());
            s.check(!(holder instanceof AbstractWarehouseRequestResolver), "the town hall's request for 500 iron cannot be met (a shortage of the colony)");
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).isEmpty(), "the colony is short of iron: no export request although another colony wants it");
            colony[0].getRequestManager().updateRequestState(token[1], RequestState.CANCELLED);
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).size() == 1, "once the shortage is gone the export request is made again");
            token[0] = exportRequests(airfield[0], colony[0]).get(0).getId();
        });
        s.step("a request that left the warehouse is withdrawn", () -> {
            IRequestManager requests = colony[0].getRequestManager();
            List<IToken<?>> children = new ArrayList<>(requests.getRequestForToken(token[0]).getChildren());
            s.check(!children.isEmpty(), "the warehouse created a delivery for the request (" + children.size() + " child request(s))");
            // The stock vanishes before a courier came and the delivery fails: MineColonies hands the parent to whoever takes it next.
            Fixture.empty(stockRack[0]);
            Fixture.empty(stockRack[1]);
            requests.updateRequestState(children.get(0), RequestState.FAILED);
            IRequestResolver<?> holder = requests.getResolverForRequest(token[0]);
            s.note("OBS after its delivery failed with an empty warehouse the request is held by " + holder.getClass().getSimpleName());
            s.check(!(holder instanceof AbstractWarehouseRequestResolver), "the request now sits with someone other than the warehouse");
            table[0].onColonyTick(colony[0]);
            s.check(gone(colony[0], token[0]), "the stray request is cancelled");
            s.check(exportRequests(airfield[0], colony[0]).isEmpty(), "and with an empty warehouse no new one is made");
            Fixture.stock(stockRack[0], Items.IRON_INGOT, 128);
            Fixture.stock(stockRack[1], Items.IRON_INGOT, 64);
            table[0].onColonyTick(colony[0]);
            List<IRequest<?>> open = exportRequests(airfield[0], colony[0]);
            s.check(open.size() == 1 && requests.getResolverForRequest(open.get(0).getId()) instanceof AbstractWarehouseRequestResolver, "once the stock is back a fresh request is held by the warehouse");
            token[0] = open.get(0).getId();
        });
        s.step("export racks are protected from pick-ups", () -> {
            s.check(!airfield[0].createPickupRequest(64, true), "a forced pick-up order creates no pick-up request");
            Collection<IToken<?>> pickups = airfield[0].getOpenRequestsByRequestableType().get(TypeToken.of(Pickup.class));
            s.check(pickups == null || pickups.isEmpty(), "the airfield has no open pick-up request");
            int mayTake = airfield[0].buildingRequiresCertainAmountOfItem(new ItemStack(Items.IRON_INGOT, 64), new ArrayList<>(), false);
            s.check(mayTake == 0, "a courier picking up at the airfield may take 0 of a stack of 64 (" + mayTake + ")");
            int mayTakeOther = airfield[0].buildingRequiresCertainAmountOfItem(new ItemStack(Items.COBBLESTONE, 64), new ArrayList<>(), false);
            s.check(mayTakeOther == 0, "the same holds for an item that is not in the table (" + mayTakeOther + ")");
            int warehouseTakes = warehouse[0].buildingRequiresCertainAmountOfItem(new ItemStack(Items.COBBLESTONE, 64), new ArrayList<>(), false);
            s.note("OBS for comparison the warehouse answers " + warehouseTakes + " for a stack that is in no list");
        });
        s.step("unmet requests become automatic wants", () -> {
            IRequestManager requests = colony[0].getRequestManager();
            WantLedger ledger = WantLedger.get(server);
            WantLedger.Key self = WantLedger.Key.of(colony[0]);
            IToken<?> unmet = hall[0].createRequest(new Stack(new ItemStack(Items.DIAMOND), 7, 7), true);
            table[0].onColonyTick(colony[0]);
            s.check(ledger.wantedAutomatically(self, Items.DIAMOND) == 0, "a request that is still being retried is not a want yet (held by " + requests.getResolverForRequest(unmet).getClass().getSimpleName() + ")");
            // The retry queue gives a request to the player after three tries; the colony is not active here, so run its clock by hand.
            int rounds = 0;
            while (rounds < 8000 && !(requests.getResolverForRequest(unmet) instanceof IPlayerRequestResolver)) { requests.tick(); rounds++; }
            s.check(requests.getResolverForRequest(unmet) instanceof IPlayerRequestResolver, "after " + rounds + " request-system ticks the town hall's request for 7 diamonds waits for the player");
            table[0].onColonyTick(colony[0]);
            s.check(ledger.wantedAutomatically(self, Items.DIAMOND) == 7, "the airfield reports it: the colony wants 7 diamonds (automatic entry)");
            s.check(ledger.wantedByOthers(other, Items.DIAMOND) == 7 && ledger.wantedByOthers(self, Items.DIAMOND) == 0, "other colonies see the want, the colony itself does not count it as demand");
            s.check(requests.getResolverForRequest(unmet) instanceof IPlayerRequestResolver && requests.getRequestForToken(unmet).getState() != RequestState.CANCELLED, "the request itself is untouched (read only)");
            requests.updateRequestState(unmet, RequestState.CANCELLED);
            table[0].onColonyTick(colony[0]);
            s.check(ledger.wantedAutomatically(self, Items.DIAMOND) == 0 && ledger.entries().size() == 1, "when the request is gone the automatic entry is gone");
            s.check(exportRequests(airfield[0], colony[0]).size() == 1, "the open export request for iron is still there after all that");
            token[0] = exportRequests(airfield[0], colony[0]).get(0).getId();
        });
        s.step("ledger survives its own save format", () -> {
            WantLedger ledger = WantLedger.get(server);
            ledger.set(new WantLedger.Key("minecraft:the_nether", 7), Items.COAL, 5);
            WantLedger copy = WantLedger.load(ledger.save(new CompoundTag(), server.registryAccess()), server.registryAccess());
            s.check(copy.entries().equals(ledger.entries()) && copy.entries().size() == 2, "ledger written and read back: " + copy.entries().size() + " entries");
            ledger.set(new WantLedger.Key("minecraft:the_nether", 7), Items.COAL, 0);
        });
        s.step("commands", () -> {
            var stack = server.createCommandSourceStack().withLevel(level).withPermission(4).withSuppressedOutput();
            String root = Airline.MOD_ID;
            s.check(server.getCommands().getDispatcher().getRoot().getChild(root) != null, "command /" + root + " is registered");
            server.getCommands().performPrefixedCommand(stack, root + " want add " + colony[0].getID() + " minecraft:coal 12");
            s.check(WantLedger.get(server).wanted(WantLedger.Key.of(colony[0]), Items.COAL) == 12, "/" + root + " want add writes the ledger");
            server.getCommands().performPrefixedCommand(stack, root + " want remove " + colony[0].getID() + " minecraft:coal");
            s.check(WantLedger.get(server).wanted(WantLedger.Key.of(colony[0]), Items.COAL) == 0, "/" + root + " want remove clears the entry");
            server.getCommands().performPrefixedCommand(stack, root + " want add 424242 minecraft:coal 1");
            s.check(WantLedger.get(server).entries().size() == 1, "an unknown colony number is refused");
            server.getCommands().performPrefixedCommand(stack, root + " export set " + colony[0].getID() + " minecraft:coal 2");
            s.check(Integer.valueOf(2).equals(table[0].table().get(new com.minecolonies.api.crafting.ItemStorage(new ItemStack(Items.COAL)))), "/" + root + " export set writes the export table");
            server.getCommands().performPrefixedCommand(stack, root + " export remove " + colony[0].getID() + " minecraft:coal");
            s.check(table[0].table().size() == 1, "/" + root + " export remove deletes the row");
            server.getCommands().performPrefixedCommand(stack, root + " want list");
            server.getCommands().performPrefixedCommand(stack, root + " export list " + colony[0].getID());
        });

        // ---- the shipped blueprint: read it and paste it the way the build tool's creative paste does ----
        s.step("load the blueprint", () -> blueprint[0] = StructurePacks.getBlueprintFuture(Airline.PACK_NAME, Airline.BLUEPRINT_PATH, level.registryAccess()));
        s.until("blueprint is read", () -> {}, () -> blueprint[0].isDone(), 600);
        s.step("paste the blueprint", () -> {
            Blueprint bp = (Blueprint) blueprint[0].get();
            s.check(bp != null, "the shipped blueprint " + Airline.BLUEPRINT_PATH + " is read from the pack");
            BlockState anchor = bp.getBlockState(bp.getPrimaryBlockOffset());
            s.check(anchor.getBlock() == Airline.AIRFIELD_HUT.get(), "its anchor is the airfield hut: " + anchor);
            int racks = 0;
            for (var info : bp.getBlockInfoAsList()) if (info.getState() != null && info.getState().getBlock() == ModBlocks.blockRack) racks++;
            int stashes = 0;
            for (var info : bp.getBlockInfoAsList()) if (info.getState() != null && info.getState().getBlock() == ModBlocks.blockStash) stashes++;
            s.check(racks == 4 && stashes == 1, "it contains four export racks and one import stash (" + racks + ", " + stashes + ")");
            // (11 high since 2026-10-18: two layers under the floor for the pit of the floor's connector, the ground kept as it is.)
            s.check(bp.getSizeX() == 34 && bp.getSizeY() == 11 && bp.getSizeZ() == 25, "its size is 34x11x25 (the whole plot, as at every level: landing pad, forecourt and hangar in the west, an empty lot in the east; two layers under the floor): " + bp.getSizeX() + "x" + bp.getSizeY() + "x" + bp.getSizeZ());
            // The rules of the network and the shape of the blueprint have to agree: nothing of it stands in the shaft over the pad or as high as the lines.
            BlockPos hutAt = bp.getPrimaryBlockOffset(), padAt = hutAt.offset(RouteRules.PAD_FROM_HUT);
            com.asteain.airline.route.Tube shaft = RouteRules.shaft(padAt);
            int inShaft = 0, tooHigh = 0, floor = 0;
            for (var info : bp.getBlockInfoAsList()) {
                if (info.getState() == null || info.getState().isAir()) continue;
                BlockPos at = info.getPos();
                if (shaft.contains(at.getX(), at.getY(), at.getZ())) inShaft++;
                if (at.getY() > padAt.getY() + RouteRules.airfieldWaypointHeight() - 8) tooHigh++;
                if (at.getY() == padAt.getY() && Math.abs(at.getX() - padAt.getX()) <= RouteRules.PAD_HALF_SIZE && Math.abs(at.getZ() - padAt.getZ()) <= RouteRules.PAD_HALF_SIZE) floor++;
            }
            s.check(inShaft == 0 && tooHigh == 0 && floor == 169, "the blueprint keeps the shaft over its pad free, stays below the lines, and has the whole 13x13 pad floor where the rules expect it (in the shaft "
                    + inShaft + ", too high " + tooHigh + ", floor blocks " + floor + ")");
            bp.setRotationMirror(RotationMirror.NONE, level);
            AbstractBlockHut<?> hut = (AbstractBlockHut<?>) anchor.getBlock();
            ServerPlayer owner = (ServerPlayer) Fixture.owner(level);
            boolean ready = hut.setup(owner, level, pastePos, bp, RotationMirror.NONE, true, Airline.PACK_NAME, Airline.BLUEPRINT_PATH);
            s.check(ready, "the hut accepts the placement (permission, colony, building registered)");
            Manager.addToQueue(new PlaceStructureOperation(new StructurePlacer(hut.getStructureHandler(level, pastePos, bp, RotationMirror.NONE, true)), owner));
        });
        s.attempt("pasted racks register", () -> {}, () -> {
            IBuilding pasted = colony[0].getServerBuildingManager().getBuilding(pastePos);
            return pasted != null && pasted.getContainers().contains(pastePos.offset(-4, 0, 0)) && pasted.getContainers().contains(pastePos.offset(-1, 0, 0))
                    && colony[0].getServerBuildingManager().getBuilding(pastePos.offset(-5, 0, -3)) != null;
        }, 400, () -> {});
        s.step("pasted airfield", () -> {
            IBuilding pasted = colony[0].getServerBuildingManager().getBuilding(pastePos);
            s.check(pasted instanceof BuildingAirfield, "pasting the blueprint registers an airfield at the anchor");
            s.check(level.getBlockEntity(pastePos) instanceof AirfieldBlockEntity, "the pasted hut has the mod's block entity");
            s.check(pasted.getBuildingLevel() == 1, "the pasted airfield is level " + pasted.getBuildingLevel());
            boolean racksThere = true, racksKnown = true;
            for (int dx = -4; dx <= -1; dx++) {
                racksThere &= level.getBlockState(pastePos.offset(dx, 0, 0)).getBlock() == ModBlocks.blockRack;
                racksKnown &= pasted.getContainers().contains(pastePos.offset(dx, 0, 0));
            }
            s.check(racksThere, "the four racks of the blueprint are in the world");
            BlockPos padAt = ((BuildingAirfield) pasted).pad();
            s.check(level.getBlockState(pastePos.below()).is(net.minecraft.world.level.block.Blocks.COBBLESTONE)
                    && level.getBlockState(padAt).is(net.minecraft.world.level.block.Blocks.COBBLESTONE) && level.getBlockState(padAt.offset(1, 0, 1)).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS)
                    && level.getBlockState(pastePos.offset(-7, 4, 0)).is(net.minecraft.world.level.block.Blocks.COBBLESTONE_STAIRS),
                    "the hangar floor, the landing pad with the mark in its middle, and the cobblestone eaves of the roof are in the world");
            s.soft("pasted racks are the building's containers", racksKnown, "containers=" + pasted.getContainers());
            // The import store comes with the blueprint (ruling of 2026-10-11): a MineColonies stash, a building of its own.
            IBuilding stash = colony[0].getServerBuildingManager().getBuilding(pastePos.offset(-5, 0, -3));
            s.soft("the stash of the blueprint is registered with the colony, and the airfield finds it as its import store",
                    stash instanceof com.minecolonies.core.colony.buildings.workerbuildings.Stash && com.asteain.airline.flight.Cargo.importStore(level, (BuildingAirfield) pasted) != null
                            && !pasted.getContainers().contains(pastePos.offset(-5, 0, -3)),
                    "building at the stash: " + stash + ", import store " + com.asteain.airline.flight.Cargo.importStore(level, (BuildingAirfield) pasted));
            // The timetable tab: with no aircraft anywhere it sends "nobody stands here, nobody comes, no aircraft of its own" (two false flags and a zero).
            com.asteain.airline.colony.TimetableModule timetable = pasted.getFirstModuleOccurance(com.asteain.airline.colony.TimetableModule.class);
            net.minecraft.network.RegistryFriendlyByteBuf sent = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
            if (timetable != null) timetable.serializeToView(sent);
            s.soft("the airfield has the timetable tab, and with no aircraft it sends two empty lines and no aircraft of its own", timetable != null && sent.readableBytes() == 3 && !sent.readBoolean() && !sent.readBoolean() && sent.readByte() == 0,
                    "module " + timetable + ", bytes " + sent.writerIndex());
            RouteNetwork.Node padNode = RouteNetwork.get(level).node(((BuildingAirfield) pasted).pad());
            s.soft("the pasted airfield's pad is on the network", padNode != null && padNode.kind() == RouteNetwork.Kind.AIRFIELD, "pad " + ((BuildingAirfield) pasted).pad() + " node " + padNode);
            s.soft("pasted hut remembers pack and path", Airline.PACK_NAME.equals(pasted.getStructurePack()) && Airline.BLUEPRINT_PATH.equals(pasted.getBlueprintPath()),
                    "pack=" + pasted.getStructurePack() + " path=" + pasted.getBlueprintPath());
            var corners = pasted.getCorners();
            s.note("OBS pasted airfield corners=" + corners.getA() + " .. " + corners.getB());
        });

        // ---- level 2: the blueprint MineColonies looks for when the airfield is upgraded, and the pad for visitors ----
        CompletableFuture<?>[] second = new CompletableFuture<?>[1];
        String path2 = Airline.BLUEPRINT_PATH.replace("1.blueprint", "2.blueprint");
        s.step("load the level 2 blueprint", () -> second[0] = StructurePacks.getBlueprintFuture(Airline.PACK_NAME, path2, level.registryAccess()));
        s.until("level 2 blueprint is read", () -> {}, () -> second[0].isDone(), 600);
        s.step("level 2", () -> {
            Blueprint first = (Blueprint) blueprint[0].get(), bp = (Blueprint) second[0].get();
            s.check(bp != null, "the level 2 blueprint is read from the pack at the path an upgrade asks for: " + path2);
            BlockPos hutAt = bp.getPrimaryBlockOffset();
            int racks = 0, stashes = 0, tooHigh = 0;
            for (var info : bp.getBlockInfoAsList()) {
                if (info.getState() == null || info.getState().isAir()) continue;
                if (info.getState().getBlock() == ModBlocks.blockRack) racks++;
                if (info.getState().getBlock() == ModBlocks.blockStash) stashes++;
                if (info.getPos().getY() > hutAt.getY() - 1 + RouteRules.airfieldWaypointHeight() - 8) tooHigh++;
            }
            s.check(bp.getBlockState(hutAt).getBlock() == Airline.AIRFIELD_HUT.get() && hutAt.equals(first.getPrimaryBlockOffset()), "its anchor is the airfield hut, at the same place as in level 1: " + hutAt);
            s.check(bp.getSizeX() == first.getSizeX() && bp.getSizeY() == first.getSizeY() && bp.getSizeZ() == first.getSizeZ() && racks == 8 && stashes == 1 && tooHigh == 0,
                    "it has the same plot as level 1 (34x11x25), eight export racks and one import stash, and stays below the lines: " + bp.getSizeX() + "x" + bp.getSizeY() + "x" + bp.getSizeZ() + ", racks " + racks + ", stashes " + stashes + ", too high " + tooHigh);
            for (BlockPos fromHut : List.of(RouteRules.PAD_FROM_HUT, RouteRules.GUEST_PAD_FROM_HUT)) {
                BlockPos padAt = hutAt.offset(fromHut);
                com.asteain.airline.route.Tube shaft = RouteRules.shaft(padAt);
                int inShaft = 0, floor = 0;
                for (var info : bp.getBlockInfoAsList()) {
                    if (info.getState() == null || info.getState().isAir()) continue;
                    BlockPos at = info.getPos();
                    if (shaft.contains(at.getX(), at.getY(), at.getZ())) inShaft++;
                    if (at.getY() == padAt.getY() && Math.abs(at.getX() - padAt.getX()) <= RouteRules.PAD_HALF_SIZE && Math.abs(at.getZ() - padAt.getZ()) <= RouteRules.PAD_HALF_SIZE) floor++;
                }
                s.check(inShaft == 0 && floor == 169, "the pad " + fromHut.toShortString() + " from the hut has its whole 13x13 floor and a free shaft (in the shaft " + inShaft + ", floor blocks " + floor + ")");
            }
            // An upgrade rebuilds the hangar on the same plot (as the buildings of the Original pack do), and what holds goods stays where it is.
            java.util.Map<BlockPos, BlockState> lower = new java.util.HashMap<>(), upper = new java.util.HashMap<>();
            for (var info : first.getBlockInfoAsList()) if (info.getState() != null && !info.getState().isAir()) lower.put(info.getPos(), info.getState());
            for (var info : bp.getBlockInfoAsList()) if (info.getState() != null && !info.getState().isAir()) upper.put(info.getPos(), info.getState());
            int kept = 0, rebuilt = 0, moved = 0, panes = 0, lanterns = 0, stoneEaves = 0;
            for (var entry : lower.entrySet()) {
                BlockState now = upper.get(entry.getKey());
                boolean holds = entry.getValue().getBlock() == ModBlocks.blockRack || entry.getValue().getBlock() == ModBlocks.blockStash || entry.getValue().getBlock() == Airline.AIRFIELD_HUT.get();
                if (holds && !entry.getValue().equals(now)) moved++;
                if (entry.getKey().getY() < 1) continue;
                if (entry.getValue().equals(now)) kept++; else rebuilt++;
            }
            for (BlockState state : upper.values()) {
                if (state.is(net.minecraft.world.level.block.Blocks.GLASS_PANE)) panes++;
                if (state.is(net.minecraft.world.level.block.Blocks.LANTERN)) lanterns++;
                if (state.is(net.minecraft.world.level.block.Blocks.STONE_BRICK_STAIRS)) stoneEaves++;
            }
            s.check(moved == 0 && rebuilt >= 100 && kept >= 100 && panes > 0 && lanterns > 0 && stoneEaves > 0,
                    "level 2 rebuilds the building (above the floor " + rebuilt + " blocks of level 1 change or go, " + kept + " stay; glass panes " + panes + ", lanterns " + lanterns
                            + ", stone brick eaves " + stoneEaves + ") and leaves the hut, the stash and the racks of level 1 where they are (moved: " + moved + ")");
            // The building: one pad at level 1, two at level 2; the second comes onto the network and leaves it with the level.
            BuildingAirfield pasted = (BuildingAirfield) colony[0].getServerBuildingManager().getBuilding(pastePos);
            RouteNetwork network = RouteNetwork.get(level);
            BlockPos guest = pasted.guestPad();
            boolean before = pasted.pads().size() == 1 && network.node(guest) == null;
            pasted.setBuildingLevel(2);
            pasted.registerPad();
            RouteNetwork.Node node = network.node(guest);
            boolean at2 = pasted.getBuildingLevel() == 2 && pasted.pads().equals(List.of(pasted.pad(), guest)) && node != null && node.kind() == RouteNetwork.Kind.AIRFIELD && node.heading() == pasted.padHeading()
                    && guest.equals(pastePos.offset(RouteRules.GUEST_PAD_FROM_HUT)) && com.asteain.airline.flight.Cargo.airfieldAt(level, guest) == pasted;
            pasted.setBuildingLevel(1);
            pasted.registerPad();
            boolean back = pasted.pads().size() == 1 && network.node(guest) == null && network.node(pasted.pad()) != null;
            s.soft("the airfield can be upgraded to level 2; its pad for visitors comes onto the network at level 2 and leaves it when the level goes down",
                    pasted.getMaxBuildingLevel() == 5 && before && at2 && back, "max level " + pasted.getMaxBuildingLevel() + ", before " + before + ", at level 2 " + at2 + " (point " + node + "), back at level 1 " + back);
        });

        // ---- the upgrade itself: the level 2 blueprint is placed over the level 1 building that stands there ----
        // (What a builder does block by block is MineColonies' own work and is not run here. This places the blueprint
        // the way a finished upgrade leaves it and looks at what the building makes of it.)
        s.step("upgrade: goods in a rack and in the stash; then the level 2 blueprint over the building", () -> {
            BuildingAirfield pasted = (BuildingAirfield) colony[0].getServerBuildingManager().getBuilding(pastePos);
            Fixture.stock((TileEntityRack) level.getBlockEntity(pastePos.offset(-4, 0, 0)), Items.IRON_INGOT, 64);
            ItemStack rest = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(com.asteain.airline.flight.Cargo.importStore(level, pasted), new ItemStack(Items.COAL, 16), false);
            s.check(rest.isEmpty() && pasted.getBuildingLevel() == 1, "64 iron lie in a rack and 16 coal in the stash of the level 1 airfield");
            com.minecolonies.core.colony.workorders.WorkOrderBuilding order = com.minecolonies.core.colony.workorders.WorkOrderBuilding.create(com.minecolonies.api.colony.workorders.WorkOrderType.UPGRADE, pasted);
            s.check(Airline.PACK_NAME.equals(order.getStructurePack()) && path2.equals(order.getStructurePath()) && order.getTargetLevel() == 2,
                    "an upgrade order for the airfield asks for the level 2 blueprint of the mod's pack: " + order.getStructurePack() + " / " + order.getStructurePath());
            Blueprint bp = (Blueprint) second[0].get();
            bp.setRotationMirror(RotationMirror.NONE, level);
            AbstractBlockHut<?> hutBlock = Airline.AIRFIELD_HUT.get();
            Manager.addToQueue(new PlaceStructureOperation(new StructurePlacer(hutBlock.getStructureHandler(level, pastePos, bp, RotationMirror.NONE, true)), (ServerPlayer) Fixture.owner(level)));
        });
        s.attempt("upgrade: placed", () -> {}, () -> level.getBlockState(pastePos.offset(19, -1, -13)).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS)
                && level.getBlockState(pastePos.offset(14, 0, 0)).getBlock() == ModBlocks.blockRack && colony[0].getServerBuildingManager().getBuilding(pastePos).getBuildingLevel() == 2, 600, () -> {});
        s.settle("upgrade: a moment", 20);
        s.step("upgrade: result", () -> {
            IBuilding upgraded = colony[0].getServerBuildingManager().getBuilding(pastePos);
            s.check(upgraded instanceof BuildingAirfield && level.getBlockEntity(pastePos) instanceof AirfieldBlockEntity, "after the upgrade the hut is still the airfield's, with the mod's block entity");
            BuildingAirfield field = (BuildingAirfield) upgraded;
            s.soft("upgrade / placing the level 2 blueprint over the building makes it level 2", field.getBuildingLevel() == 2, "level " + field.getBuildingLevel());
            int ironLeft = 0;
            TileEntityRack rack = level.getBlockEntity(pastePos.offset(-4, 0, 0)) instanceof TileEntityRack r ? r : null;
            if (rack != null) for (int slot = 0; slot < rack.getInventory().getSlots(); slot++) if (rack.getInventory().getStackInSlot(slot).is(Items.IRON_INGOT)) ironLeft += rack.getInventory().getStackInSlot(slot).getCount();
            int coalLeft = com.asteain.airline.flight.Cargo.count(com.asteain.airline.flight.Cargo.importStore(level, field), Items.COAL);
            s.soft("upgrade / what lay in a rack and in the stash of level 1 is still there", ironLeft == 64 && coalLeft == 16, "iron in the rack " + ironLeft + ", coal in the stash " + coalLeft);
            List<BlockPos> racks = List.of(pastePos.offset(-4, 0, 0), pastePos.offset(-3, 0, 0), pastePos.offset(-2, 0, 0), pastePos.offset(-1, 0, 0),
                    pastePos.offset(-5, 0, 0), pastePos.offset(-5, 0, -1), pastePos.offset(14, 0, 0), pastePos.offset(15, 0, 0));
            boolean known = true, there = true;
            for (BlockPos pos : racks) {
                there &= level.getBlockState(pos).getBlock() == ModBlocks.blockRack;
                known &= field.getContainers().contains(pos);
            }
            s.soft("upgrade / the eight racks of level 2 stand and are the building's containers", there && known, "there " + there + ", registered " + known + ", containers " + field.getContainers().size());
            s.soft("upgrade / the hangar is rebuilt: glass panes where the fence windows were, stone brick eaves a layer higher, the pad for visitors paved",
                    level.getBlockState(pastePos.offset(-6, 1, -2)).is(net.minecraft.world.level.block.Blocks.GLASS_PANE) && level.getBlockState(pastePos.offset(-7, 5, 0)).is(net.minecraft.world.level.block.Blocks.STONE_BRICK_STAIRS)
                            && level.getBlockState(pastePos.offset(-7, 4, 0)).isAir() && level.getBlockState(field.guestPad()).is(net.minecraft.world.level.block.Blocks.COBBLESTONE),
                    "window " + level.getBlockState(pastePos.offset(-6, 1, -2)).getBlock() + ", eaves " + level.getBlockState(pastePos.offset(-7, 5, 0)).getBlock() + ", old eaves " + level.getBlockState(pastePos.offset(-7, 4, 0)).getBlock()
                            + ", middle of the new pad " + level.getBlockState(field.guestPad()).getBlock());
            RouteNetwork.Node node = RouteNetwork.get(level).node(field.guestPad());
            s.soft("upgrade / the pad for visitors is on the network at once (not only with the next colony tick)", node != null && node.kind() == RouteNetwork.Kind.AIRFIELD, "point " + node);
            String next = field.getBuildingLevel() < field.getMaxBuildingLevel()
                    ? com.minecolonies.core.colony.workorders.WorkOrderBuilding.create(com.minecolonies.api.colony.workorders.WorkOrderType.UPGRADE, field).getStructurePath() : "(no higher level)";
            s.soft("upgrade / the next upgrade asks for the blueprint of the level after this one (or there is none)", next.equals("(no higher level)") || next.endsWith("airfield3.blueprint"),
                    "the building names " + field.getBlueprintPath() + "; the next upgrade would ask for " + next);
            IBuilding stash = colony[0].getServerBuildingManager().getBuilding(pastePos.offset(-5, 0, -3));
            s.soft("upgrade / the stash is still a building of the colony and still the airfield's import store", stash instanceof com.minecolonies.core.colony.buildings.workerbuildings.Stash
                    && com.asteain.airline.flight.Cargo.importBuilding(level, field) == stash, "stash " + stash);
        });

        // ---- levels 3 to 5: the blueprints, and placing each over the building in turn ----
        CompletableFuture<?>[] higher = new CompletableFuture<?>[6];
        int[] racksAt = {0, 4, 8, 12, 16, 20};
        s.step("levels 3 to 5: load the blueprints", () -> {
            for (int n = 3; n <= 5; n++) higher[n] = StructurePacks.getBlueprintFuture(Airline.PACK_NAME, Airline.BLUEPRINT_PATH.replace("1.blueprint", n + ".blueprint"), level.registryAccess());
        });
        s.until("levels 3 to 5: blueprints are read", () -> {}, () -> higher[3].isDone() && higher[4].isDone() && higher[5].isDone(), 600);
        s.step("levels 3 to 5: the blueprints", () -> {
            Blueprint lower = (Blueprint) second[0].get();
            for (int n = 3; n <= 5; n++) {
                Blueprint bp = (Blueprint) higher[n].get();
                s.check(bp != null, "the level " + n + " blueprint is read from the pack");
                BlockPos hutAt = bp.getPrimaryBlockOffset();
                java.util.Map<BlockPos, BlockState> was = new java.util.HashMap<>(), now = new java.util.HashMap<>();
                for (var info : lower.getBlockInfoAsList()) if (info.getState() != null && !info.getState().isAir()) was.put(info.getPos(), info.getState());
                int racks = 0, stashes = 0, tooHigh = 0, tiles = 0, tilesWithMaterial = 0;
                for (var info : bp.getBlockInfoAsList()) {
                    if (info.getState() == null || info.getState().isAir()) continue;
                    now.put(info.getPos(), info.getState());
                    if (info.getState().getBlock() == ModBlocks.blockRack) racks++;
                    if (info.getState().getBlock() == ModBlocks.blockStash) stashes++;
                    if (info.getPos().getY() > hutAt.getY() - 1 + RouteRules.airfieldWaypointHeight() - 8) tooHigh++;
                    if (BuiltInRegistries.BLOCK.getKey(info.getState().getBlock()).getNamespace().equals("domum_ornamentum") && info.getState().hasBlockEntity()) {
                        tiles++;
                        if (info.hasTileEntityData() && info.getTileEntityData().getCompound("textureData").size() >= 1) tilesWithMaterial++;
                    }
                }
                int moved = 0, kept = 0, rebuilt = 0;
                for (var entry : was.entrySet()) {
                    BlockState after = now.get(entry.getKey());
                    boolean holds = entry.getValue().getBlock() == ModBlocks.blockRack || entry.getValue().getBlock() == ModBlocks.blockStash || entry.getValue().getBlock() == Airline.AIRFIELD_HUT.get();
                    if (holds && !entry.getValue().equals(after)) moved++;
                    if (entry.getKey().getY() < 1) continue;
                    if (entry.getValue().equals(after)) kept++; else rebuilt++;
                }
                boolean pads = true;
                for (BlockPos fromHut : List.of(RouteRules.PAD_FROM_HUT, RouteRules.GUEST_PAD_FROM_HUT)) {
                    BlockPos padAt = hutAt.offset(fromHut);
                    com.asteain.airline.route.Tube shaft = RouteRules.shaft(padAt);
                    int inShaft = 0, floor = 0;
                    for (BlockPos at : now.keySet()) {
                        if (shaft.contains(at.getX(), at.getY(), at.getZ())) inShaft++;
                        if (at.getY() == padAt.getY() && Math.abs(at.getX() - padAt.getX()) <= RouteRules.PAD_HALF_SIZE && Math.abs(at.getZ() - padAt.getZ()) <= RouteRules.PAD_HALF_SIZE) floor++;
                    }
                    // From level 3 the floor has the 3x3 pit of its connector (2026-10-18), three south of the middle.
                    pads &= inShaft == 0 && floor == (n >= 3 ? 169 - 9 : 169);
                }
                s.check(bp.getSizeX() == lower.getSizeX() && bp.getSizeY() == lower.getSizeY() && bp.getSizeZ() == lower.getSizeZ() && hutAt.equals(lower.getPrimaryBlockOffset())
                                && bp.getBlockState(hutAt).getBlock() == Airline.AIRFIELD_HUT.get() && racks == racksAt[n] && stashes == 1 && tooHigh == 0 && pads,
                        "level " + n + ": the same plot and hut place as the level before, " + racks + " racks, one stash, nothing as high as the lines, both pads whole with free shafts");
                s.check(moved == 0 && rebuilt >= 100 && tiles > 0 && tiles == tilesWithMaterial,
                        "level " + n + " rebuilds the building (above the floor " + rebuilt + " blocks change or go, " + kept + " stay), leaves hut, stash and the racks of level " + (n - 1)
                                + " where they are (moved: " + moved + "), and every one of its " + tiles + " Domum Ornamentum blocks says what it is made of");
                lower = bp;
            }
        });
        for (int n = 3; n <= 5; n++) {
            final int target = n;
            s.step("level " + n + ": placed over the building", () -> {
                BuildingAirfield field = (BuildingAirfield) colony[0].getServerBuildingManager().getBuilding(pastePos);
                String asked = com.minecolonies.core.colony.workorders.WorkOrderBuilding.create(com.minecolonies.api.colony.workorders.WorkOrderType.UPGRADE, field).getStructurePath();
                s.check(asked.endsWith("airfield" + target + ".blueprint"), "at level " + field.getBuildingLevel() + " an upgrade order asks for " + asked);
                Blueprint bp = (Blueprint) higher[target].get();
                bp.setRotationMirror(RotationMirror.NONE, level);
                AbstractBlockHut<?> hutBlock = Airline.AIRFIELD_HUT.get();
                Manager.addToQueue(new PlaceStructureOperation(new StructurePlacer(hutBlock.getStructureHandler(level, pastePos, bp, RotationMirror.NONE, true)), (ServerPlayer) Fixture.owner(level)));
            });
            s.attempt("level " + n + ": placed", () -> {}, () -> colony[0].getServerBuildingManager().getBuilding(pastePos).getBuildingLevel() == target
                    && colony[0].getServerBuildingManager().getBuilding(pastePos).getContainers().size() >= racksAt[target], 600, () -> {});
            s.settle("level " + n + ": a moment", 20);
            s.step("level " + n + ": result", () -> {
                BuildingAirfield field = (BuildingAirfield) colony[0].getServerBuildingManager().getBuilding(pastePos);
                int ironLeft = 0;
                TileEntityRack stocked = level.getBlockEntity(pastePos.offset(-4, 0, 0)) instanceof TileEntityRack r ? r : null;
                if (stocked != null) for (int slot = 0; slot < stocked.getInventory().getSlots(); slot++) if (stocked.getInventory().getStackInSlot(slot).is(Items.IRON_INGOT)) ironLeft += stocked.getInventory().getStackInSlot(slot).getCount();
                int coalLeft = com.asteain.airline.flight.Cargo.count(com.asteain.airline.flight.Cargo.importStore(level, field), Items.COAL);
                int racks = 0;
                for (BlockPos spot : field.getContainers()) if (level.getBlockState(spot).getBlock() == ModBlocks.blockRack) racks++;
                // A roof tile of the hangar's eaves: a Domum Ornamentum block whose block entity knows its materials.
                BlockPos eaves = pastePos.offset(-7, 5, 0);
                net.minecraft.world.level.block.entity.BlockEntity tile = level.getBlockEntity(eaves);
                boolean tiled = BuiltInRegistries.BLOCK.getKey(level.getBlockState(eaves).getBlock()).toString().equals("domum_ornamentum:shingle_flat") && tile != null
                        && tile.saveWithoutMetadata(level.registryAccess()).getCompound("textureData").size() >= 2;
                s.soft("level " + target + " / placed over the building: it is level " + target + ", the goods of level 1 are still in their rack and in the stash, " + racksAt[target] + " racks are its containers, and the roof is tiled",
                        field.getBuildingLevel() == target && ironLeft == 64 && coalLeft == 16 && racks == racksAt[target] && tiled && level.getBlockEntity(pastePos) instanceof AirfieldBlockEntity
                                && field.pads().size() == 2 && RouteNetwork.get(level).node(field.guestPad()) != null,
                        "level " + field.getBuildingLevel() + ", iron " + ironLeft + ", coal " + coalLeft + ", racks " + racks + ", eaves " + level.getBlockState(eaves).getBlock()
                                + (tile == null ? " without a block entity" : " with " + tile.saveWithoutMetadata(level.registryAccess()).getCompound("textureData")));
            });
        }

        // ---- an airfield placed mirrored and turned: the pads are where the building has them, the heading points away from the hut ----
        BlockPos mirrorPos = pastePos.offset(-56, 0, 0);
        CompletableFuture<?>[] third = new CompletableFuture<?>[1];
        s.step("mirrored: load the level 2 blueprint again", () -> third[0] = StructurePacks.getBlueprintFuture(Airline.PACK_NAME, path2, level.registryAccess()));
        s.until("mirrored: blueprint is read", () -> {}, () -> third[0].isDone(), 600);
        s.step("mirrored: place it mirrored and turned a quarter", () -> {
            Blueprint bp = (Blueprint) third[0].get();
            bp.setRotationMirror(RotationMirror.MIR_R90, level);
            AbstractBlockHut<?> hutBlock = Airline.AIRFIELD_HUT.get();
            ServerPlayer owner = (ServerPlayer) Fixture.owner(level);
            boolean ready = hutBlock.setup(owner, level, mirrorPos, bp, RotationMirror.MIR_R90, true, Airline.PACK_NAME, path2);
            s.check(ready, "the hut accepts the mirrored placement");
            Manager.addToQueue(new PlaceStructureOperation(new StructurePlacer(hutBlock.getStructureHandler(level, mirrorPos, bp, RotationMirror.MIR_R90, true)), owner));
        });
        s.attempt("mirrored: placed", () -> {}, () -> colony[0].getServerBuildingManager().getBuilding(mirrorPos) instanceof BuildingAirfield field && field.getBuildingLevel() == 2
                && level.getBlockState(field.guestPad()).is(net.minecraft.world.level.block.Blocks.COBBLESTONE) && field.getContainers().size() >= 8, 600, () -> {});
        s.settle("mirrored: a moment", 20);
        s.step("mirrored: result", () -> {
            IBuilding placed = colony[0].getServerBuildingManager().getBuilding(mirrorPos);
            s.check(placed instanceof BuildingAirfield, "the mirrored placement registers an airfield");
            BuildingAirfield field = (BuildingAirfield) placed;
            field.registerPad();
            s.soft("mirrored / the building knows that it is mirrored and turned, and is level 2", field.getRotationMirror() == RotationMirror.MIR_R90 && field.getBuildingLevel() == 2,
                    field.getRotationMirror() + ", level " + field.getBuildingLevel());
            int heading = field.padHeading();
            double noseX = Math.cos(Math.toRadians(heading)), noseZ = Math.sin(Math.toRadians(heading));
            boolean floors = true, shafts = true, away = true, nodes = true;
            StringBuilder seen = new StringBuilder("heading " + heading);
            for (BlockPos pad : field.pads()) {
                // The floor: 13x13 of planks and cobblestone, with the cobblestone cross through the middle and planks on the diagonal.
                int floor = 0, cross = 0;
                for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
                    BlockState state = level.getBlockState(pad.offset(dx, 0, dz));
                    if (state.is(net.minecraft.world.level.block.Blocks.OAK_PLANKS) || state.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)) floor++;
                    if ((dx == 0 || dz == 0) && Math.abs(dx) <= 5 && Math.abs(dz) <= 5 && state.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)) cross++;
                }
                boolean diagonal = level.getBlockState(pad.offset(1, 0, 1)).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS) && level.getBlockState(pad.offset(-3, 0, 2)).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS);
                floors &= floor == 169 && cross == 21 && diagonal;
                // The shaft over it is free.
                com.asteain.airline.route.Tube shaft = RouteRules.shaft(pad);
                int inShaft = 0;
                for (int dx = -9; dx <= 9; dx++) for (int dz = -9; dz <= 9; dz++) for (int dy = 1; dy <= RouteRules.airfieldWaypointHeight() + 8; dy++) {
                    BlockPos at = pad.offset(dx, dy, dz);
                    if (shaft.contains(at.getX(), at.getY(), at.getZ()) && RouteNetwork.solid(level, at, level.getBlockState(at))) inShaft++;
                }
                shafts &= inShaft == 0;
                // The nose points away from the hut.
                double dot = noseX * (mirrorPos.getX() - pad.getX()) + noseZ * (mirrorPos.getZ() - pad.getZ());
                away &= dot < -10;
                RouteNetwork.Node node = RouteNetwork.get(level).node(pad);
                nodes &= node != null && node.kind() == RouteNetwork.Kind.AIRFIELD && node.heading() == heading;
                seen.append("; pad ").append(pad.toShortString()).append(": floor ").append(floor).append(", cross ").append(cross).append(", in the shaft ").append(inShaft).append(String.format(", hut lies %.0f behind the nose", -dot));
            }
            s.soft("mirrored / both pads are where the building has them (whole floor, the cross through the middle), with free shafts", field.pads().size() == 2 && floors && shafts, seen.toString());
            s.soft("mirrored / the heading an aircraft rests in points away from the hut, and both pads are on the network with it", heading % 90 == 0 && away && nodes, seen.toString());
            s.soft("mirrored / the mirror matters: the pads are not where a building that was only turned would have them", !field.pad().equals(RouteRules.pad(mirrorPos, RotationMirror.MIR_R90.rotation())),
                    "mirrored " + field.pad().toShortString() + ", only turned " + RouteRules.pad(mirrorPos, RotationMirror.MIR_R90.rotation()).toShortString());
        });

        s.step("pilot walked to the airfield", () -> {
            double now = Math.sqrt(pilotEntity[0].distanceToSqr(airfieldPos.getX() + 0.5, airfieldPos.getY(), airfieldPos.getZ() + 0.5));
            Object ai = pilot[0].getJob() == null ? null : pilot[0].getJob().getWorkerAI();
            s.note("OBS pilot distance to the hut: " + String.format("%.1f", pilotStartDistance[0]) + " -> " + String.format("%.1f", now) + ", AI=" + (ai == null ? null : ai.getClass().getSimpleName()));
            s.check(ai != null, "the pilot's AI exists");
        });
        s.attempt("pilot reaches the airfield", () -> {}, () -> pilotEntity[0].distanceToSqr(airfieldPos.getX() + 0.5, airfieldPos.getY(), airfieldPos.getZ() + 0.5) < 6 * 6, 1200, () -> {});
        s.step("pilot at the airfield", () -> {
            double now = Math.sqrt(pilotEntity[0].distanceToSqr(airfieldPos.getX() + 0.5, airfieldPos.getY(), airfieldPos.getZ() + 0.5));
            s.soft("pilot walks to the airfield", now < 6, "distance " + String.format("%.1f", pilotStartDistance[0]) + " -> " + String.format("%.1f", now) + " blocks");
            s.check(pilotEntity[0].isAlive() && pilot[0].getJob() instanceof JobPilot, "the pilot is alive and still a pilot");
        });

        // ---- two real colonies: what the second one wants (by command, and read from its unmet requests) is collected in the first ----
        IColony[] colonyB = new IColony[1];
        AbstractBuilding[] airfieldB = new AbstractBuilding[1];
        s.step("a second colony with an airfield", () -> {
            BlockPos originB = origin.offset(0, 0, 640);
            Fixture.platform(level, originB, 12, 12);
            colonyB[0] = Fixture.create(level, originB, "Airline test B " + Fixture.existing());
            IBuilding hallB = Fixture.townHall(colonyB[0], originB);
            s.check(colonyB[0].getID() != colony[0].getID(), "second colony " + colonyB[0].getID() + " next to colony " + colony[0].getID());
            airfieldB[0] = Fixture.placeHut(level, colonyB[0], Airline.AIRFIELD_HUT.get(), originB.offset(-6, 0, 0));
            airfieldB[0].setBuildingLevel(1);
            ExportStockModule tableB = airfieldB[0].getFirstModuleOccurance(ExportStockModule.class);
            tableB.addMinimumStock(new ItemStack(Items.COAL), 0);

            // the first colony can spare coal and redstone; iron is put aside for this part
            WantLedger ledger = WantLedger.get(server);
            ledger.clear(null);
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).isEmpty(), "nothing is wanted: the first colony has no open export request");
            Fixture.stock(stockRack[1], Items.COAL, 20);
            Fixture.stock(stockRack[1], Items.REDSTONE, 30);
            table[0].addMinimumStock(new ItemStack(Items.COAL), 0);
            table[0].addMinimumStock(new ItemStack(Items.REDSTONE), 0);

            var console = server.createCommandSourceStack().withLevel(level).withPermission(4).withSuppressedOutput();
            server.getCommands().performPrefixedCommand(console, Airline.MOD_ID + " want add " + colonyB[0].getID() + " minecraft:coal 12");
            s.check(ledger.wanted(WantLedger.Key.of(colonyB[0]), Items.COAL) == 12, "by command: colony " + colonyB[0].getID() + " wants 12 coal");
            IRequestManager requestsB = colonyB[0].getRequestManager();
            IToken<?> unmet = hallB.createRequest(new Stack(new ItemStack(Items.REDSTONE), 9, 9), true);
            int rounds = 0;
            while (rounds < 8000 && !(requestsB.getResolverForRequest(unmet) instanceof IPlayerRequestResolver)) { requestsB.tick(); rounds++; }
            tableB.onColonyTick(colonyB[0]);
            s.check(ledger.wantedAutomatically(WantLedger.Key.of(colonyB[0]), Items.REDSTONE) == 9, "read from its requests: colony " + colonyB[0].getID() + " is short of 9 redstone");
            s.check(exportRequests(airfieldB[0], colonyB[0]).isEmpty(), "the second colony does not collect for its own want (coal is in its table too)");

            table[0].onColonyTick(colony[0]);
            List<IRequest<?>> open = exportRequests(airfield[0], colony[0]);
            int coal = 0, redstone = 0;
            for (IRequest<?> request : open) {
                Stack asked = (Stack) request.getRequest();
                if (asked.getStack().is(Items.COAL)) coal += asked.getCount();
                if (asked.getStack().is(Items.REDSTONE)) redstone += asked.getCount();
                s.check(colony[0].getRequestManager().getResolverForRequest(request.getId()) instanceof AbstractWarehouseRequestResolver, "request for " + asked.getCount() + " x " + asked.getStack().getItem() + " is held by the warehouse");
            }
            s.check(open.size() == 2 && coal == 12 && redstone == 9, "the first colony collects exactly what the second wants: 12 coal and 9 redstone (" + coal + ", " + redstone + ")");
        });
        s.step("the second colony is deleted", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(colonyB[0]);
            com.minecolonies.api.colony.IColonyManager.getInstance().deleteColonyByDimension(colonyB[0].getID(), false, level.dimension());
            WantLedger ledger = WantLedger.get(server);
            s.check(ledger.wanted(keyB, Items.COAL) == 0 && ledger.wanted(keyB, Items.REDSTONE) == 0 && ledger.entries().isEmpty(), "a deleted colony leaves the ledger: " + ledger.entries());
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).isEmpty(), "and the first colony withdraws the requests made for it");
            table[0].removeMinimumStock(new ItemStack(Items.COAL));
            table[0].removeMinimumStock(new ItemStack(Items.REDSTONE));
            s.check(table[0].table().size() == 1, "the export table is back to its single row");
            ledger.set(other, Items.IRON_INGOT, 100);
        });

        if (withCourier) courier(s, server, level, colony, airfield, warehouse, table, courier, courierHutPos, other, airfieldPos, pastePos);

        // ---- the colony's own periodic tick: without a player the colony is not ticked, so call the step an active colony runs ----
        s.step("the colony's building tick reaches the export table", () -> {
            WantLedger.get(server).set(other, Items.IRON_INGOT, 0);
            table[0].onColonyTick(colony[0]);
            int racks = InventoryUtils.getItemCountInItemHandler(airfield[0].getItemHandlerCap(), stack -> stack.is(Items.IRON_INGOT));
            WantLedger.get(server).set(other, Items.IRON_INGOT, racks + 5);
            s.note("OBS colony state without a player: " + colony[0].getState() + " (MineColonies runs the building tick only for an active colony)");
            // Colony#worldTickSlow calls exactly this for an active colony, every 500 ticks.
            colony[0].getServerBuildingManager().onColonyTick(colony[0]);
            List<IRequest<?>> open = exportRequests(airfield[0], colony[0]);
            s.check(open.size() == 1 && ((Stack) open.get(0).getRequest()).getCount() == 5, "MineColonies' building tick alone makes the export table ask for the 5 missing ingots");
            WantLedger.get(server).set(other, Items.IRON_INGOT, 0);
            colony[0].getServerBuildingManager().onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).isEmpty(), "and withdraw the request when the want is gone");
        });
        s.settle("let a courier finish what it carries", 200);

        s.step("leave a state for the reload check", () -> {
            // The world is saved with one export request still open (20 ingots on their way), so that the reload check
            // and the check without the mod both meet an unfinished request.
            int racksNow = InventoryUtils.getItemCountInItemHandler(airfield[0].getItemHandlerCap(), stack -> stack.is(Items.IRON_INGOT));
            WantLedger.get(server).clear(null);
            WantLedger.get(server).set(other, Items.IRON_INGOT, racksNow + 20);
            table[0].onColonyTick(colony[0]);
            List<IRequest<?>> left = exportRequests(airfield[0], colony[0]);
            s.check(left.size() == 1 && ((Stack) left.get(0).getRequest()).getCount() == 20, "the world is left with one open export request for 20 ingots");
            Files.writeString(Path.of(STATE_FILE), String.join("\n",
                    "colony=" + colony[0].getID(),
                    "airfield=" + airfieldPos.getX() + "," + airfieldPos.getY() + "," + airfieldPos.getZ(),
                    "pasted=" + pastePos.getX() + "," + pastePos.getY() + "," + pastePos.getZ(),
                    "pilot=" + pilot[0].getId(),
                    "rows=" + table[0].table().size(),
                    "wanted=" + (racksNow + 20),
                    "onRacks=" + racksNow) + "\n");
            s.note("OBS state written for the reload check: colony " + colony[0].getID() + ", airfield " + airfieldPos);
        });
        return s;
    }

    /**
     * The real thing: a courier carries the surplus from the warehouse to the export racks. Time-boxed; when the courier does
     * not deliver, the result is recorded as an observation and the request-level checks above stand in (see the contract).
     * <p>
     * The upgrade checks leave 16 coal in the stash of the pasted airfield, 50 blocks away. A stash asks the colony's couriers
     * to collect what lies in it, so the one courier walks there and back to the warehouse before it carries the first batch.
     * The time box of the first batch allows for that walk, and where the coal went is written down.
     */
    private static void courier(Script s, MinecraftServer server, ServerLevel level, IColony[] colony, AbstractBuilding[] airfield, AbstractBuilding[] warehouse,
                                ExportStockModule[] table, ICitizenData[] courier, BlockPos courierHutPos, WantLedger.Key other, BlockPos airfieldPos, BlockPos pastePos) {
        AbstractBuilding[] hut = new AbstractBuilding[1];
        Mob[] entity = new Mob[1];
        boolean[] delivered = new boolean[2];
        int[] before = new int[2];
        int[] coal = new int[2];
        s.step("courier hut", () -> {
            // withdraw what is open: its delivery was created while the colony had no courier
            WantLedger.get(server).set(other, Items.IRON_INGOT, 0);
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).isEmpty(), "no export request is open before the courier starts");
            hut[0] = Fixture.placeHut(level, colony[0], ModBlocks.blockHutDeliveryman, courierHutPos);
            hut[0].setBuildingLevel(1);
            hut[0].setCorners(courierHutPos.offset(-2, 0, -2), courierHutPos.offset(2, 2, 2));
            courier[0] = Fixture.spawn(colony[0], level, courierHutPos.offset(0, 0, 3));
        });
        s.until("courier entity", () -> {}, () -> (entity[0] = Fixture.entity(courier[0])) != null, 200);
        s.step("hire the courier", () -> {
            s.check(hut[0].getFirstModuleOccurance(WorkerBuildingModule.class).assignCitizen(courier[0]), "a courier is hired");
            s.check(warehouse[0].getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier[0]), "the courier serves the warehouse");
        });
        s.settle("courier settles in", 100);
        s.step("ask for the first batch", () -> {
            before[0] = InventoryUtils.getItemCountInItemHandler(airfield[0].getItemHandlerCap(), stack -> stack.is(Items.IRON_INGOT));
            before[1] = Fixture.count(warehouse[0], Items.IRON_INGOT);
            WantLedger.get(server).set(other, Items.IRON_INGOT, before[0] + 36);
            table[0].onColonyTick(colony[0]);
            List<IRequest<?>> open = exportRequests(airfield[0], colony[0]);
            s.check(open.size() == 1 && ((Stack) open.get(0).getRequest()).getCount() == 36, "with a courier: a request for 36 is open");
            coal[0] = stashCoal(level, colony[0], pastePos);
            coal[1] = Fixture.count(warehouse[0], Items.COAL);
            s.note("OBS courier run starts: export racks=" + before[0] + " warehouse=" + before[1] + " courier job=" + (courier[0].getJob() == null ? null : courier[0].getJob().getClass().getSimpleName())
                    + "; coal waiting in the stash of the pasted airfield=" + coal[0] + ", coal in the warehouse=" + coal[1]);
        });
        s.attempt("courier delivers the first batch", () -> {},
                () -> delivered[0] = InventoryUtils.getItemCountInItemHandler(airfield[0].getItemHandlerCap(), stack -> stack.is(Items.IRON_INGOT)) >= before[0] + 36, 6000,
                () -> s.note("OBS courier did not deliver the first batch within 6000 ticks; courier at " + entity[0].blockPosition()
                        + " state=" + (courier[0].getJob() == null || courier[0].getJob().getWorkerAI() == null ? null : courier[0].getJob().getWorkerAI().getState())));
        s.step("first batch", () -> {
            int racks = InventoryUtils.getItemCountInItemHandler(airfield[0].getItemHandlerCap(), stack -> stack.is(Items.IRON_INGOT));
            int stock = Fixture.count(warehouse[0], Items.IRON_INGOT);
            s.note("OBS after the first courier run: export racks=" + racks + " warehouse=" + stock + " delivered=" + delivered[0]
                    + "; coal in the stash of the pasted airfield " + coal[0] + " -> " + stashCoal(level, colony[0], pastePos)
                    + ", coal in the warehouse " + coal[1] + " -> " + Fixture.count(warehouse[0], Items.COAL));
            if (!delivered[0]) return;
            s.check(racks == before[0] + 36, "the courier brought exactly the 36 asked for: export racks " + before[0] + " -> " + racks);
            s.check(stock == before[1] - 36 && stock >= 64, "the warehouse gave 36 and keeps at least its stack of 64: " + before[1] + " -> " + stock);
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).isEmpty(), "the want is met: no further request");
            // second batch: the airfield already holds the item
            before[0] = racks;
            before[1] = stock;
            WantLedger.get(server).set(other, Items.IRON_INGOT, racks + 40);
            table[0].onColonyTick(colony[0]);
            s.check(exportRequests(airfield[0], colony[0]).size() == 1, "a second batch is requested while the airfield already holds the item");
        });
        s.attempt("courier delivers the second batch", () -> {},
                () -> !delivered[0] || (delivered[1] = InventoryUtils.getItemCountInItemHandler(airfield[0].getItemHandlerCap(), stack -> stack.is(Items.IRON_INGOT)) >= before[0] + 40), 3000,
                () -> s.note("OBS courier did not deliver the second batch within 3000 ticks"));
        s.step("second batch", () -> {
            if (!delivered[0]) { s.note("OBS courier delivery NOT verified; the request-level checks stand in"); return; }
            int racks = InventoryUtils.getItemCountInItemHandler(airfield[0].getItemHandlerCap(), stack -> stack.is(Items.IRON_INGOT));
            int stock = Fixture.count(warehouse[0], Items.IRON_INGOT);
            s.note("OBS after the second courier run: export racks=" + racks + " warehouse=" + stock + " delivered=" + delivered[1]);
            s.soft("second batch delivered by the courier", delivered[1] && racks == before[0] + 40 && stock == before[1] - 40, "export racks " + before[0] + " -> " + racks + ", warehouse " + before[1] + " -> " + stock);
            table[0].onColonyTick(colony[0]);
            s.soft("nothing beyond the want is collected", exportRequests(airfield[0], colony[0]).isEmpty() && racks <= before[0] + 40, "open requests=" + exportRequests(airfield[0], colony[0]).size());
        });
    }

    /** Coal in the stash (the import store) of the pasted airfield; -1 when the airfield or its stash is not there. */
    private static int stashCoal(ServerLevel level, IColony colony, BlockPos pastePos) {
        if (!(colony.getServerBuildingManager().getBuilding(pastePos) instanceof BuildingAirfield field)) return -1;
        var store = com.asteain.airline.flight.Cargo.importStore(level, field);
        return store == null ? -1 : com.asteain.airline.flight.Cargo.count(store, Items.COAL);
    }

    static List<IRequest<?>> exportRequests(AbstractBuilding airfield, IColony colony) {
        Collection<IToken<?>> tokens = airfield.getOpenRequestsByRequestableType().get(TypeToken.of(Stack.class));
        List<IRequest<?>> out = new ArrayList<>();
        if (tokens == null) return out;
        for (IToken<?> t : new ArrayList<>(tokens)) {
            IRequest<?> request = colony.getRequestManager().getRequestForToken(t);
            if (request != null) out.add(request);
        }
        return out;
    }

    static boolean gone(IColony colony, IToken<?> token) {
        try {
            IRequest<?> request = colony.getRequestManager().getRequestForToken(token);
            return request == null || request.getState() == RequestState.CANCELLED;
        } catch (IllegalArgumentException unknown) {
            return true;
        }
    }
}
