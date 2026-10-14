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

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister<BuildingEntry> BUILDINGS = DeferredRegister.create(CommonMinecoloniesAPIImpl.BUILDINGS, MOD_ID);
    private static final DeferredRegister<JobEntry> JOBS = DeferredRegister.create(CommonMinecoloniesAPIImpl.JOBS, MOD_ID);

    public static final DeferredBlock<BlockHutAirfield> AIRFIELD_HUT = BLOCKS.register(HUT_NAME, BlockHutAirfield::new);
    public static final DeferredItem<ItemBlockHut> AIRFIELD_HUT_ITEM =
            ITEMS.register(HUT_NAME, () -> new ItemBlockHut(AIRFIELD_HUT.get(), new Item.Properties()));

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
            BLOCK_ENTITIES.register(BUILDING_NAME, () -> BlockEntityType.Builder.of(AirfieldBlockEntity::new, AIRFIELD_HUT.get()).build(null));

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

    /** The name of the pilot's look (a MineColonies "model type"). Its path is also the first part of the texture file names. */
    public static final ResourceLocation PILOT_LOOK = ResourceLocation.fromNamespaceAndPath(MOD_ID, "pilot");

    public Airline(IEventBus modBus, ModContainer container) {
        // The client class is only touched here, and only on a client.
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) com.asteain.airline.client.PilotLook.hook();
        container.registerConfig(ModConfig.Type.COMMON, RouteRules.SPEC);
        container.registerConfig(ModConfig.Type.COMMON, Cargo.SPEC, MOD_ID + "-cargo.toml");
        Flights.bootstrap();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        BUILDINGS.register(modBus);
        JOBS.register(modBus);
        modBus.addListener(Airline::registerCapabilities);
        modBus.addListener(Airline::commonSetup);
        modBus.addListener(Airline::creativeTab);
        modBus.addListener(Airline::registerPayloads);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> AirlineCommand.register(event.getDispatcher(), event.getBuildContext()));
        NeoForge.EVENT_BUS.addListener(RouteNetwork::onLevelTick);
        NeoForge.EVENT_BUS.addListener(RouteNetwork::onNeighborNotify);
    }

    /** What the client sends to the server: the aircraft tab's requests. */
    private static void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(com.asteain.airline.network.AircraftOrderPayload.TYPE, com.asteain.airline.network.AircraftOrderPayload.STREAM_CODEC,
                com.asteain.airline.network.AircraftOrderPayload::handle);
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
