package com.asteain.airline.route;

import com.asteain.airline.Airline;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * The item of a buoy. Like MineColonies' colony sign it can only be set down within reach of the chain that exists
 * already (another buoy, or an airfield's landing pad), and the player is told when it is too far. A buoy placed by a
 * command or by a machine is not held to this.
 */
public class BuoyItem extends BlockItem {
    private final BuoyBlock buoy;

    public BuoyItem(BuoyBlock block, Properties properties) {
        super(block, properties);
        this.buoy = block;
    }

    @Override
    protected boolean canPlace(BlockPlaceContext context, BlockState state) {
        if (!super.canPlace(context, state)) return false;
        if (!(context.getLevel() instanceof ServerLevel level) || context.getPlayer() == null) return true;
        final RouteNetwork network = RouteNetwork.find(level);
        double nearest = Double.MAX_VALUE;
        int reach = 0;
        if (network != null) {
            for (final RouteNetwork.Node node : network.nodes()) {
                if (RouteRules.connects(context.getClickedPos(), RouteRules.reach(buoy.tier()), node.pos(), node.reach())) return true;
                final double distance = Math.sqrt(node.pos().distSqr(context.getClickedPos()));
                if (distance < nearest) {
                    nearest = distance;
                    reach = RouteRules.reach(buoy.tier()) + node.reach();
                }
            }
        }
        final String key = "message." + Airline.MOD_ID + ".buoy.";
        context.getPlayer().sendSystemMessage(nearest == Double.MAX_VALUE ? Component.translatable(key + "no_network")
                : Component.translatable(key + "too_far", (int) Math.ceil(nearest), reach));
        level.playSound(null, context.getClickedPos(), SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.BLOCKS, 1.0F, 0.5F);
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("message." + Airline.MOD_ID + ".buoy.tip", RouteRules.reach(buoy.tier())).withStyle(ChatFormatting.DARK_GREEN));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
