package com.asteain.airline.route;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import com.asteain.airline.Airline;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.fml.ModList;

/**
 * A buoy: a point of the air network. It joins the network of its dimension at the place where it is put and leaves it
 * when it goes away from there (broken, pushed, or taken up into a Sable ship). It needs no support: it is an ordinary
 * block, so it stays where it is when what it was placed against is removed.
 *
 * <p>The grade (1 to 3) decides how far it reaches ({@link RouteRules#reach}).</p>
 */
public class BuoyBlock extends Block {
    /** The lamp burns while at least one of the buoy's lines can be flown (as a colony sign shows that it is connected). */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    /**
     * A lamp room in a spruce frame hung under canvas gas bags, a mast through the middle: see tools/make_buoy_art.py.
     * The grades differ in their outline: one slim bag, one wide flat bag, four bags at the corners.
     */
    private static final VoxelShape[] SHAPES = {
            Block.box(4, 0.5, 4, 12, 16, 12),
            Shapes.or(Block.box(3, 0.5, 3, 13, 10, 13), Block.box(1, 10, 1, 15, 15, 15), Block.box(6, 15, 6, 10, 16, 10)),
            Shapes.or(Block.box(3, 0.5, 3, 13, 9, 13), Block.box(2, 9, 2, 14, 10, 14), Block.box(7, 10, 7, 9, 16, 9),
                    Block.box(0.5, 10, 0.5, 5.5, 16, 5.5), Block.box(10.5, 10, 0.5, 15.5, 16, 5.5),
                    Block.box(0.5, 10, 10.5, 5.5, 16, 15.5), Block.box(10.5, 10, 10.5, 15.5, 16, 15.5))};
    private final int tier;

    public BuoyBlock(int tier) {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.5F).sound(SoundType.WOOD).noOcclusion().lightLevel(state -> state.getValue(LIT) ? 15 : 0));
        if (tier < 1 || tier > SHAPES.length) throw new IllegalArgumentException("buoy grade " + tier);
        this.tier = tier;
        registerDefaultState(defaultBlockState().setValue(LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[tier - 1];
    }

    public int tier() {
        return tier;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel server && !oldState.is(this) && !inShip(server, pos)) RouteNetwork.get(server).addBuoy(pos, tier);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel server && !newState.is(this)) {
            final RouteNetwork network = RouteNetwork.find(server);
            if (network != null) network.remove(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Set down by a player: what it joined is told to that player once its lines have been looked at. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel server && placer instanceof ServerPlayer player) {
            final RouteNetwork network = RouteNetwork.find(server);
            if (network != null && network.node(pos) != null) network.watch(pos, player.getUUID());
        }
    }

    /** Used with an empty hand: the buoy says how its lines stand and which airfields it leads to (a colony sign shows this on its board). */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server) {
            final RouteNetwork network = RouteNetwork.find(server);
            if (network != null) for (final Component line : network.describe(server, pos, false)) player.sendSystemMessage(line);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** A buoy that is part of a Sable ship is not a point: its blocks live in the ship's own far-away space. */
    private static boolean inShip(ServerLevel level, BlockPos pos) {
        return ModList.get().isLoaded(Airline.SABLE_ID) && SableOnly.inShip(level, pos);
    }

    /** Only loaded when Sable is installed. */
    private static final class SableOnly {
        static boolean inShip(ServerLevel level, BlockPos pos) {
            try {
                return dev.ryanhcode.sable.Sable.HELPER.getContaining(level, pos) != null;
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
        }
    }
}
