package com.asteain.airline.network;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.BuildingAirfield;
import com.asteain.airline.flight.Flights;
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
 * From the airfield's window to the server: in the aircraft tab, build an aircraft of a design on the airfield's own pad
 * (or build that design instead on the site there), call the building off, switch self-repair on or off; in the
 * timetable tab, let the aircraft standing there leave now. Only a player who may manage the colony's huts is heard;
 * the design is looked up by name, never taken on trust.
 */
public record AircraftOrderPayload(BlockPos hut, String action, String design) implements CustomPacketPayload {
    public static final String BUILD = "build", STOP = "stop", REPAIR_ON = "repair_on", REPAIR_OFF = "repair_off", DEPART = "depart";

    public static final Type<AircraftOrderPayload> TYPE = new Type<>(Airline.id("aircraft_order"));
    public static final StreamCodec<ByteBuf, AircraftOrderPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, AircraftOrderPayload::hut,
            ByteBufCodecs.stringUtf8(16), AircraftOrderPayload::action,
            ByteBufCodecs.stringUtf8(64), AircraftOrderPayload::design,
            AircraftOrderPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AircraftOrderPayload order, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) return;
            final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, order.hut());
            if (colony == null || !(colony.getServerBuildingManager().getBuilding(order.hut()) instanceof BuildingAirfield airfield)) return;
            if (!colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS)) {
                player.sendSystemMessage(Component.translatable("message." + Airline.MOD_ID + ".yard.not_allowed"));
                return;
            }
            switch (order.action()) {
                case BUILD -> Flights.requestAircraft(level, airfield, order.design(), player);
                case STOP -> Flights.stopAircraft(level, airfield, player);
                case REPAIR_ON, REPAIR_OFF -> {
                    Flights.selfRepair(level, airfield, order.action().equals(REPAIR_ON));
                    player.sendSystemMessage(Component.translatable("message." + Airline.MOD_ID + ".yard." + order.action()));
                }
                case DEPART -> Flights.departNow(level, airfield, player);
                default -> { }
            }
            airfield.markDirty();
        });
    }
}
