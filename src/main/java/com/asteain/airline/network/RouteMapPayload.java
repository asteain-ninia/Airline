package com.asteain.airline.network;

import com.asteain.airline.Airline;
import com.asteain.airline.client.RouteMapClient;
import com.asteain.airline.route.RouteMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** From the server to a player: the lines of the air network in a dimension ({@link RouteMap}), for the player's map. */
public record RouteMapPayload(ResourceLocation dimension, List<RouteMap.Line> lines) implements CustomPacketPayload {
    public static final Type<RouteMapPayload> TYPE = new Type<>(Airline.id("route_map"));
    public static final StreamCodec<FriendlyByteBuf, RouteMapPayload> STREAM_CODEC = StreamCodec.of(RouteMapPayload::write, RouteMapPayload::read);

    private static void write(FriendlyByteBuf buf, RouteMapPayload payload) {
        buf.writeResourceLocation(payload.dimension);
        buf.writeVarInt(payload.lines.size());
        for (final RouteMap.Line line : payload.lines) {
            buf.writeLong(line.a().asLong());
            buf.writeLong(line.b().asLong());
            buf.writeByte(line.open());
            buf.writeByte(line.state());
        }
    }

    private static RouteMapPayload read(FriendlyByteBuf buf) {
        final ResourceLocation dimension = buf.readResourceLocation();
        final int count = buf.readVarInt();
        final List<RouteMap.Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) lines.add(new RouteMap.Line(BlockPos.of(buf.readLong()), BlockPos.of(buf.readLong()), buf.readByte(), buf.readByte()));
        return new RouteMapPayload(dimension, lines);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RouteMapPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RouteMapClient.receive(payload.dimension, payload.lines));
    }
}
