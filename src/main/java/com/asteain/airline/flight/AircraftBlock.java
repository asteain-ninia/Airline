package com.asteain.airline.flight;

import com.asteain.airline.Airline;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/**
 * The aircraft's own block: one per aircraft, in the middle of its deck. It remembers the aircraft's home and flight
 * ({@link AircraftBlockEntity}); while the aircraft flies it holds Sable's loading ticket, while it is landed it keeps
 * the ship fixed to the world, and it runs the autopilot. Without the mods the flying part needs it is just a block.
 */
public final class AircraftBlock extends BaseEntityBlock {
    public static final MapCodec<AircraftBlock> CODEC = simpleCodec(AircraftBlock::new);

    public AircraftBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    public AircraftBlock() {
        this(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0F).sound(SoundType.WOOD));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // (Building an aircraft is the airfield's business alone: it is asked for in the airfield's window, which sets this block
    // down on the pad. The block itself begins nothing and chooses nothing.)

    /**
     * Broken by a player: what the fuel store holds drops where the block stood. (Only then: when the blocks of a finished
     * aircraft are made a ship, this block moves into the ship with its store, and nothing must drop.)
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AircraftBlockEntity aircraft) net.minecraft.world.Containers.dropContents(level, pos, aircraft.store);
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** Used with an empty hand: the aircraft's fuel store opens (fuel only). */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof AircraftBlockEntity aircraft)) return net.minecraft.world.InteractionResult.PASS;
        if (!level.isClientSide) player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, who) -> new FuelMenu(id, inventory, aircraft.store),
                net.minecraft.network.chat.Component.translatable("container." + Airline.MOD_ID + ".fuel_store")));
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Used with fuel in the hand: it goes into the aircraft's tank (as much as there is room for). Not while the aircraft is still being built. */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (level instanceof ServerLevel server && Flights.available() && level.getBlockEntity(pos) instanceof AircraftBlockEntity aircraft && Flights.isFuel(stack) && Flights.afloat(server, pos)) {
            final int taken = Flights.refuel(server, aircraft, stack, player instanceof ServerPlayer serverPlayer ? serverPlayer : null);
            if (taken > 0 && !player.getAbilities().instabuild) stack.shrink(taken);
            return net.minecraft.world.ItemInteractionResult.SUCCESS;
        }
        return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * The block goes away (broken on purpose, or the ship is taken apart): nothing will run this aircraft any more, so its
     * drives are switched off and its ticket is given back. It is not fixed here: the ship may be going away as well.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!newState.is(this) && level instanceof ServerLevel server && Flights.available()) {
            // The block entity is still there (this runs before it is taken away): what the flight carried is told to the ledger.
            Flights.abandon(server, pos, level.getBlockEntity(pos) instanceof AircraftBlockEntity aircraft ? aircraft : null);
            if (level.getBlockEntity(pos) instanceof AircraftBlockEntity site && site.yard != null) Flights.buildingStopped(server, site);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AircraftBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != Airline.AIRCRAFT_BLOCK_ENTITY.get() || !Flights.available()) return null;
        return (l, pos, s, entity) -> Flights.tick((ServerLevel) l, (AircraftBlockEntity) entity);
    }
}
