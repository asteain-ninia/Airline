package com.asteain.airline;

import com.asteain.airline.block.AirfieldBlockEntity;
import com.asteain.airline.block.BlockHutAirfield;
import com.asteain.airline.colony.BuildingAirfield;
import com.asteain.airline.colony.ExportStockModule;
import com.asteain.airline.colony.ExportStockModuleView;
import com.asteain.airline.colony.JobPilot;
import com.asteain.airline.colony.PilotWorkModule;
import com.asteain.airline.colony.TimetableModule;
import com.asteain.airline.colony.TimetableModuleView;
import com.asteain.airline.command.AirlineCommand;
import com.asteain.airline.flight.AircraftBlock;
import com.asteain.airline.flight.AircraftBlockEntity;
import com.asteain.airline.flight.Cargo;
import com.asteain.airline.flight.Flights;
import com.asteain.airline.ledger.WantLedger;
import com.asteain.airline.route.BuoyBlock;
import com.asteain.airline.route.BuoyItem;
import com.asteain.airline.route.RouteNetwork;
import com.asteain.airline.route.RouteRules;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.colony.jobs.ModJobs;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.creativetab.ModCreativeTabs;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.eventbus.events.colony.ColonyDeletedModEvent;
import com.minecolonies.api.items.ItemBlockHut;
import com.minecolonies.api.sounds.ModSoundEvents;
import com.minecolonies.api.util.IItemHandlerCapProvider;
import com.minecolonies.apiimp.CommonMinecoloniesAPIImpl;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.moduleviews.WorkerBuildingModuleView;
import com.minecolonies.core.colony.buildings.views.EmptyView;
import com.minecolonies.core.colony.jobs.views.DefaultJobView;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;

/**
 * Air freight between MineColonies colonies. Stage 3: the airfield (a MineColonies building), the pilot (a job) and the
 * collection of warehouse surplus onto the airfield's export racks. No aircraft yet.
 *
 * <p>The registration layout follows the add-on Volcanic Mason (https://github.com/ferriarnus/VolcanicMason,
 * Copyright (c) Ferri_Arnus, LGPL-3.0): a hut block with its own block entity type, a building entry and a job entry
 * registered into MineColonies' registries from another mod.</p>
 */
@Mod(Airline.MOD_ID)
public final class Airline {
    /** The mod's id. This is the only place in Java where the id is written as text. */
    public static final String MOD_ID = "airline";

    /** Registry paths (not ids). The hut name is read by the hut block's super constructor, so it must stay a constant. */
    public static final String HUT_NAME = "blockhutairfield";
    public static final String BUILDING_NAME = "airfield";
    public static final String JOB_NAME = "pilot";
    /** Buoys of grade 1 to 3 are registered as this name followed by the grade. */
    public static final String BUOY_NAME = "buoy_";

    /** Ids of the mods the flying part needs. Without them only the flying part is switched off. */
    public static final String SABLE_ID = "sable";
    public static final String AERONAUTICS_ID = "aeronautics";
    public static final String SIMULATED_ID = "simulated";
    public static final String COLONY_PATCH_SERVER_ID = "colonypatch_server";
    public static final String AIRCRAFT_NAME = "aircraft";

    /** The structure pack shipped in this jar (blueprints/&lt;id&gt;/airfield/pack.json) and the hut's level 1 blueprint in it. */
    public static final String PACK_NAME = "Airline";
    public static final String BLUEPRINT_PATH = "infrastructure/airfield1.blueprint";

    /** The first level's blueprint of a kind of airfield (or anchorage) in this mod's pack. */
    public static String blueprintPath(com.asteain.airline.route.Facility facility) {
        return "infrastructure/" + facility.schematic + "1.blueprint";
    }

    /** The hut block's name: "blockhutairfield" (the small airfield, as it always was), "blockhutmediumairfield", and so on. */
    public static String hutName(com.asteain.airline.route.Facility facility) {
        return "blockhut" + facility.schematic;
    }

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister<BuildingEntry> BUILDINGS = DeferredRegister.create(CommonMinecoloniesAPIImpl.BUILDINGS, MOD_ID);
    private static final DeferredRegister<JobEntry> JOBS = DeferredRegister.create(CommonMinecoloniesAPIImpl.JOBS, MOD_ID);
    private static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);

    /** The window of an aircraft's fuel store. */
    public static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<com.asteain.airline.flight.FuelMenu>> FUEL_MENU =
            MENUS.register("fuel_store", () -> new net.minecraft.world.inventory.MenuType<>(com.asteain.airline.flight.FuelMenu::new, net.minecraft.world.flag.FeatureFlags.VANILLA_SET));

    public static final DeferredBlock<BlockHutAirfield> AIRFIELD_HUT = BLOCKS.register(HUT_NAME, () -> new BlockHutAirfield());
    public static final DeferredItem<ItemBlockHut> AIRFIELD_HUT_ITEM =
            ITEMS.register(HUT_NAME, () -> new ItemBlockHut(AIRFIELD_HUT.get(), new Item.Properties()));
    /** The huts of the medium and the large airfield and of the anchorage (2026-10-14; their blueprints are provisional). */
    public static final DeferredBlock<BlockHutAirfield> MEDIUM_AIRFIELD_HUT = BLOCKS.register(hutName(com.asteain.airline.route.Facility.MEDIUM_AIRFIELD),
            id -> new BlockHutAirfield(com.asteain.airline.route.Facility.MEDIUM_AIRFIELD));
    public static final DeferredBlock<BlockHutAirfield> LARGE_AIRFIELD_HUT = BLOCKS.register(hutName(com.asteain.airline.route.Facility.LARGE_AIRFIELD),
            id -> new BlockHutAirfield(com.asteain.airline.route.Facility.LARGE_AIRFIELD));
    public static final DeferredBlock<BlockHutAirfield> ANCHORAGE_HUT = BLOCKS.register(hutName(com.asteain.airline.route.Facility.ANCHORAGE),
            id -> new BlockHutAirfield(com.asteain.airline.route.Facility.ANCHORAGE));
    public static final DeferredItem<ItemBlockHut> MEDIUM_AIRFIELD_HUT_ITEM = ITEMS.register(hutName(com.asteain.airline.route.Facility.MEDIUM_AIRFIELD),
            () -> new ItemBlockHut(MEDIUM_AIRFIELD_HUT.get(), new Item.Properties()));
    public static final DeferredItem<ItemBlockHut> LARGE_AIRFIELD_HUT_ITEM = ITEMS.register(hutName(com.asteain.airline.route.Facility.LARGE_AIRFIELD),
            () -> new ItemBlockHut(LARGE_AIRFIELD_HUT.get(), new Item.Properties()));
    public static final DeferredItem<ItemBlockHut> ANCHORAGE_HUT_ITEM = ITEMS.register(hutName(com.asteain.airline.route.Facility.ANCHORAGE),
            () -> new ItemBlockHut(ANCHORAGE_HUT.get(), new Item.Properties()));

    /** The three grades of buoy (index 0 is grade 1) and their items. */
    public static final List<DeferredBlock<BuoyBlock>> BUOYS = buoys();
    public static final List<DeferredItem<BlockItem>> BUOY_ITEMS = BUOYS.stream()
            .map(buoy -> ITEMS.<BlockItem>register(buoy.getId().getPath(), () -> new BuoyItem(buoy.get(), new Item.Properties()))).toList();

    private static List<DeferredBlock<BuoyBlock>> buoys() {
        final List<DeferredBlock<BuoyBlock>> all = new ArrayList<>();
        for (int tier = 1; tier <= RouteRules.TIERS; tier++) {
            final int grade = tier;
            all.add(BLOCKS.register(BUOY_NAME + grade, () -> new BuoyBlock(grade)));
        }
        return List.copyOf(all);
    }

    /**
     * The aircraft's own block: one on every aircraft, in the middle of its deck. Set down on the middle of an airfield's
     * landing pad it begins the building of an aircraft there (flight/Shipyard); the spawn command makes one with its aircraft.
     */
    public static final DeferredBlock<AircraftBlock> AIRCRAFT = BLOCKS.register(AIRCRAFT_NAME, () -> new AircraftBlock());
    public static final DeferredItem<BlockItem> AIRCRAFT_ITEM = ITEMS.register(AIRCRAFT_NAME, () -> new BlockItem(AIRCRAFT.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AircraftBlockEntity>> AIRCRAFT_BLOCK_ENTITY =
            BLOCK_ENTITIES.register(AIRCRAFT_NAME, () -> BlockEntityType.Builder.of(AircraftBlockEntity::new, AIRCRAFT.get()).build(null));

    /** MineColonies' own building block entity type only accepts MineColonies' huts, so the airfield brings its own type. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AirfieldBlockEntity>> AIRFIELD_BLOCK_ENTITY =
            BLOCK_ENTITIES.register(BUILDING_NAME, () -> BlockEntityType.Builder.of(AirfieldBlockEntity::new,
                    AIRFIELD_HUT.get(), MEDIUM_AIRFIELD_HUT.get(), LARGE_AIRFIELD_HUT.get(), ANCHORAGE_HUT.get()).build(null));

    public static final DeferredHolder<JobEntry, JobEntry> PILOT = JOBS.register(JOB_NAME, () -> new JobEntry.Builder()
            .setJobProducer(JobPilot::new)
            .setJobViewProducer(() -> DefaultJobView::new)
            .setRegistryName(id(JOB_NAME))
            .createJobEntry());

    /**
     * MineColonies numbers module producers in the order they are created (a counter in ModuleProducer) and sends those numbers
     * over the network (building views, minimum stock messages). Touching BuildingModules here makes MineColonies create all of
     * its own producers first, on the server and on the client alike, so this mod's producers get the numbers after them on both sides.
     */
    @SuppressWarnings("unused")
    private static final Object MINECOLONIES_MODULES_FIRST = BuildingModules.MIN_STOCK;

    /** Module keys are global in MineColonies and are written into the building's save data: never rename them lightly. */
    public static final BuildingEntry.ModuleProducer<PilotWorkModule, WorkerBuildingModuleView> PILOT_WORK =
            new BuildingEntry.ModuleProducer<>(MOD_ID + "_pilot_work",
                    () -> new PilotWorkModule(PILOT.get(), Skill.Adaptability, Skill.Focus, true, b -> 1),
                    () -> WorkerBuildingModuleView::new);
    public static final BuildingEntry.ModuleProducer<ExportStockModule, ExportStockModuleView> EXPORT_STOCK =
            new BuildingEntry.ModuleProducer<>(MOD_ID + "_export_stock", ExportStockModule::new, () -> ExportStockModuleView::new);
    /** Made after the two above, on both sides: the numbers MineColonies gives the producers must stay the same on server and client. */
    public static final BuildingEntry.ModuleProducer<TimetableModule, TimetableModuleView> TIMETABLE =
            new BuildingEntry.ModuleProducer<>(MOD_ID + "_timetable", TimetableModule::new, () -> TimetableModuleView::new);
    /** The aircraft tab (building an aircraft is asked for here, and only here). Made after the timetable, for the same reason. */
    public static final BuildingEntry.ModuleProducer<com.asteain.airline.colony.AircraftModule, com.asteain.airline.colony.AircraftModuleView> AIRCRAFT_TAB =
            new BuildingEntry.ModuleProducer<>(MOD_ID + "_aircraft", com.asteain.airline.colony.AircraftModule::new, () -> com.asteain.airline.colony.AircraftModuleView::new);

    public static final DeferredHolder<BuildingEntry, BuildingEntry> AIRFIELD = BUILDINGS.register(BUILDING_NAME, () -> new BuildingEntry.Builder()
            .setBuildingBlock(AIRFIELD_HUT.get())
            .setBuildingProducer(BuildingAirfield::new)
            .setBuildingViewProducer(() -> EmptyView::new)
            .setRegistryName(id(BUILDING_NAME))
            .addBuildingModuleProducer(PILOT_WORK)
            .addBuildingModuleProducer(EXPORT_STOCK)
            .addBuildingModuleProducer(TIMETABLE)
            .addBuildingModuleProducer(AIRCRAFT_TAB)
            .createBuildingEntry());
    /** The medium and the large airfield: the same pages as the small one. */
    public static final DeferredHolder<BuildingEntry, BuildingEntry> MEDIUM_AIRFIELD = BUILDINGS.register(com.asteain.airline.route.Facility.MEDIUM_AIRFIELD.schematic, () -> new BuildingEntry.Builder()
            .setBuildingBlock(MEDIUM_AIRFIELD_HUT.get())
            .setBuildingProducer((colony, pos) -> new BuildingAirfield(colony, pos, com.asteain.airline.route.Facility.MEDIUM_AIRFIELD))
            .setBuildingViewProducer(() -> EmptyView::new)
            .setRegistryName(id(com.asteain.airline.route.Facility.MEDIUM_AIRFIELD.schematic))
            .addBuildingModuleProducer(PILOT_WORK)
            .addBuildingModuleProducer(EXPORT_STOCK)
            .addBuildingModuleProducer(TIMETABLE)
            .addBuildingModuleProducer(AIRCRAFT_TAB)
            .createBuildingEntry());
    public static final DeferredHolder<BuildingEntry, BuildingEntry> LARGE_AIRFIELD = BUILDINGS.register(com.asteain.airline.route.Facility.LARGE_AIRFIELD.schematic, () -> new BuildingEntry.Builder()
            .setBuildingBlock(LARGE_AIRFIELD_HUT.get())
            .setBuildingProducer((colony, pos) -> new BuildingAirfield(colony, pos, com.asteain.airline.route.Facility.LARGE_AIRFIELD))
            .setBuildingViewProducer(() -> EmptyView::new)
            .setRegistryName(id(com.asteain.airline.route.Facility.LARGE_AIRFIELD.schematic))
            .addBuildingModuleProducer(PILOT_WORK)
            .addBuildingModuleProducer(EXPORT_STOCK)
            .addBuildingModuleProducer(TIMETABLE)
            .addBuildingModuleProducer(AIRCRAFT_TAB)
            .createBuildingEntry());
    /**
     * The anchorage: no pilot, no aircraft page (it builds none) and no export list: it has no racks or store of its own,
     * aircraft load and unload there from and into those of the colony's airfield (the user's ruling of 2026-10-15).
     */
    public static final DeferredHolder<BuildingEntry, BuildingEntry> ANCHORAGE = BUILDINGS.register(com.asteain.airline.route.Facility.ANCHORAGE.schematic, () -> new BuildingEntry.Builder()
            .setBuildingBlock(ANCHORAGE_HUT.get())
            .setBuildingProducer((colony, pos) -> new BuildingAirfield(colony, pos, com.asteain.airline.route.Facility.ANCHORAGE))
            .setBuildingViewProducer(() -> EmptyView::new)
            .setRegistryName(id(com.asteain.airline.route.Facility.ANCHORAGE.schematic))
            .addBuildingModuleProducer(TIMETABLE)
            .createBuildingEntry());

    /** The building entry of a kind of airfield (or the anchorage). */
    public static BuildingEntry building(com.asteain.airline.route.Facility facility) {
        return switch (facility) {
            case SMALL_AIRFIELD -> AIRFIELD.get();
            case MEDIUM_AIRFIELD -> MEDIUM_AIRFIELD.get();
            case LARGE_AIRFIELD -> LARGE_AIRFIELD.get();
            case ANCHORAGE -> ANCHORAGE.get();
        };
    }

    /** The name of the pilot's look (a MineColonies "model type"). Its path is also the first part of the texture file names. */
    public static final ResourceLocation PILOT_LOOK = ResourceLocation.fromNamespaceAndPath(MOD_ID, "pilot");

    public Airline(IEventBus modBus, ModContainer container) {
        // The client class is only touched here, and only on a client.
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) com.asteain.airline.client.PilotLook.hook();
        container.registerConfig(ModConfig.Type.COMMON, RouteRules.SPEC);
        container.registerConfig(ModConfig.Type.COMMON, Cargo.SPEC, MOD_ID + "-cargo.toml");
        // Per world (saves/<world>/serverconfig/): the design tools, off unless a world that is not played switches them on.
        container.registerConfig(ModConfig.Type.SERVER, com.asteain.airline.flight.DesignTools.SPEC, MOD_ID + "-server.toml");
        Flights.bootstrap();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        BUILDINGS.register(modBus);
        JOBS.register(modBus);
        MENUS.register(modBus);
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) modBus.addListener(com.asteain.airline.client.FuelScreen::register);
        modBus.addListener(Airline::registerCapabilities);
        modBus.addListener(Airline::commonSetup);
        modBus.addListener(Airline::creativeTab);
        modBus.addListener(Airline::registerPayloads);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> AirlineCommand.register(event.getDispatcher(), event.getBuildContext()));
        NeoForge.EVENT_BUS.addListener(RouteNetwork::onLevelTick);
        NeoForge.EVENT_BUS.addListener(RouteNetwork::onNeighborNotify);
        // The lines of the network for the players' maps (JourneyMap, when they have it).
        NeoForge.EVENT_BUS.addListener(com.asteain.airline.route.RouteMap::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.asteain.airline.route.RouteMap::onLogin);
        NeoForge.EVENT_BUS.addListener(com.asteain.airline.route.RouteMap::onChangedDimension);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent event) -> com.asteain.airline.route.RouteMap.forget());
    }

    /** What the client sends to the server (the aircraft tab's requests), and what the server sends (the lines of the network, for the map). */
    private static void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        final net.neoforged.neoforge.network.registration.PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(com.asteain.airline.network.AircraftOrderPayload.TYPE, com.asteain.airline.network.AircraftOrderPayload.STREAM_CODEC,
                com.asteain.airline.network.AircraftOrderPayload::handle);
        registrar.playToClient(com.asteain.airline.network.RouteMapPayload.TYPE, com.asteain.airline.network.RouteMapPayload.STREAM_CODEC,
                com.asteain.airline.network.RouteMapPayload::handle);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Same exposure MineColonies gives its own huts (hut chest plus the racks of the building). */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, AIRFIELD_BLOCK_ENTITY.get(), IItemHandlerCapProvider::getItemHandlerCap);
    }

    /**
     * MineColonies builds its citizen voice table from its own job list and reads it without a null check
     * (SoundUtils#playSoundAtCitizenWith), so a job from another mod must be added to the table. The pilot borrows the courier's voices.
     */
    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            var voices = ModSoundEvents.CITIZEN_SOUND_EVENTS.get(ModJobs.DELIVERY_ID.getPath());
            if (voices != null) ModSoundEvents.CITIZEN_SOUND_EVENTS.putIfAbsent(JOB_NAME, voices);
            // A deleted colony wants nothing any more.
            IMinecoloniesAPI.getInstance().getEventBus().subscribe(ColonyDeletedModEvent.class, deleted -> {
                if (deleted.getColony().getWorld() instanceof ServerLevel level)
                    WantLedger.get(level.getServer()).forget(WantLedger.Key.of(deleted.getColony()));
            });
        });
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == ModCreativeTabs.HUTS.getKey()) {
            event.accept(AIRFIELD_HUT_ITEM.get());
            for (final DeferredItem<BlockItem> buoy : BUOY_ITEMS) event.accept(buoy.get());
            event.accept(AIRCRAFT_ITEM.get());
        }
    }
}
