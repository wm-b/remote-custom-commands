package inceris.rcc.fabric;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record RccPayload(byte[] data) implements CustomPayload {
    public static final Id<RccPayload> ID = new Id<>(Identifier.of("rcc", "bridge"));
    public static final PacketCodec<PacketByteBuf, RccPayload> CODEC = PacketCodec.of(
        (payload, buf) -> buf.writeBytes(payload.data()),
        buf -> { if (buf.readableBytes() > 30000) throw new IllegalArgumentException("RCC packet too large");
            byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes); return new RccPayload(bytes); });
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
