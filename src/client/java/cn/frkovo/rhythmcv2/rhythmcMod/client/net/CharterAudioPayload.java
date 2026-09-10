package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * rhythmc:charter_audio 自定义 payload：包装原始字节（ChartMaker 同款范式），
 * 通道内自行做 [int opcode][string sessionId][payload] 帧解析。
 */
public record CharterAudioPayload(PacketByteBuf buf) implements CustomPayload {
    public static final CustomPayload.Id<CharterAudioPayload> ID = new CustomPayload.Id<>(
            Identifier.of(CharterAudioChannel.CHANNEL));

    public static final PacketCodec<PacketByteBuf, CharterAudioPayload> CODEC = PacketCodec.of(
            (value, dest) -> dest.writeBytes(value.buf),
            src -> new CharterAudioPayload(new PacketByteBuf(src.readBytes(src.readableBytes())))
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
