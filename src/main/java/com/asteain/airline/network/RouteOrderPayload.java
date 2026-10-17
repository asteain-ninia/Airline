package com.asteain.airline.network;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.BuildingAirfield;
import com.asteain.airline.route.ColonyRoutes;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.permissions.Action;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * From the routes tab to the server (2026-10-18): draw a route from the airfield's colony to another colony, or take it
 * off. Only a player who may manage the colony's huts is heard.
 */
public record RouteOrderPayload(BlockPos hut, int colony, boolean draw) implements CustomPacketPayload {
    public static final Type<RouteOrderPayload> TYPE = new Type<>(Airline.id("route_order"));
    public static final StreamCodec<ByteBuf, RouteOrderPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RouteOrderPayload::hut,
            ByteBufCodecs.VAR_INT, RouteOrderPayload::colony,
            ByteBufCodecs.BOOL, RouteOrderPayload::draw,
            RouteOrderPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RouteOrderPayload order, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) return;
            final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, order.hut());
            if (colony == null || !(colony.getServerBuildingManager().getBuilding(order.hut()) instanceof BuildingAirfield airfield)) return;
            if (!colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS)) {
                player.sendSystemMessage(Component.translatable("message." + Airline.MOD_ID + ".yard.not_allowed"));
                return;
            }
            final IColony other = IColonyManager.getInstance().getColonyByDimension(order.colony(), level.dimension());
            if (other == null || other == colony) return;
            final ColonyRoutes routes = ColonyRoutes.get(level.getServer());
            if (order.draw()) routes.draw(level, colony.getID(), other.getID());
            else routes.remove(level, colony.getID(), other.getID());
            player.sendSystemMessage(Component.translatable("message." + Airline.MOD_ID + ".route." + (order.draw() ? "drawn" : "removed"), colony.getName(), other.getName()));
            airfield.markDirty();
        });
    }
}
