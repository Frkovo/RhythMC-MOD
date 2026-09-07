package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

import cn.frkovo.rhythmcv2.rhythmcMod.client.audio.AudioLibrary;
import cn.frkovo.rhythmcv2.rhythmcMod.client.audio.CharterAudioEngine;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * charter_audio 客户端端：握手（HELLO/HELLO_ACK + sessionId 绑定）、CHART_META 音频匹配、
 * Transport 指令消费、STATE 每 10 tick 上报（与插件对账周期一致）。
 * 所有音频操作只由服务端指令驱动（mod 本地不直改时钟，避免双时钟源分叉，§11.5）。
 */
public final class CharterAudioClient {

    private static final CharterAudioClient INSTANCE = new CharterAudioClient();
    private static final String MOD_VERSION = "1.0.0";

    private final CharterAudioEngine engine = new CharterAudioEngine();
    private final AtomicInteger tickCounter = new AtomicInteger();

    private volatile boolean handshakeOk;
    private volatile String serverVersion = "";
    private volatile String sessionId = "";
    private volatile boolean registered;

    public static CharterAudioClient get() {
        return INSTANCE;
    }

    public boolean isHandshakeOk() {
        return handshakeOk;
    }

    public CharterAudioEngine engine() {
        return engine;
    }

    public void register() {
        if (registered) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.playC2S().register(CharterAudioPayload.ID, CharterAudioPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CharterAudioPayload.ID, CharterAudioPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(CharterAudioPayload.ID, (payload, context) ->
                context.client().execute(() -> handleServerPacket(payload.buf())));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            resetHandshake();
            // 延迟握手，等服务端插件消息通道就绪（Bukkit registerOutgoing/Incoming 在 enable 时完成）
            new Thread(() -> {
                sleep(1000);
                if (MinecraftClient.getInstance().getNetworkHandler() != null) {
                    sendHello();
                }
            }, "CharterAudio-Hello").start();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetHandshake());
        // STATE 每 10 tick
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (tickCounter.incrementAndGet() % 10 == 0) {
                sendState();
            }
        });
    }

    private void resetHandshake() {
        handshakeOk = false;
        sessionId = "";
        serverVersion = "";
        engine.stop();
    }

    // ---- 发送 ----

    private PacketByteBuf frame(int opcode) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(opcode);
        writeUtf8(buf, sessionId);
        return buf;
    }

    private void send(PacketByteBuf buf) {
        try {
            ClientPlayNetworking.send(new CharterAudioPayload(buf));
        } catch (RuntimeException ignored) {
            // 通道不可用（未连接/服务端无本插件）
        }
    }

    private void sendHello() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(CharterAudioChannel.OP_HELLO);
        writeUtf8(buf, ""); // 握手期无 sessionId
        buf.writeInt(CharterAudioChannel.PROTOCOL_VERSION);
        writeUtf8(buf, "RhythMC-MOD " + MOD_VERSION);
        buf.writeInt(CharterAudioChannel.CAP_SEEK | CharterAudioChannel.CAP_LOOP
                | CharterAudioChannel.CAP_SPEED);
        send(buf);
    }

    private void sendState() {
        if (!handshakeOk || sessionId.isEmpty()) {
            return;
        }
        PacketByteBuf buf = frame(CharterAudioChannel.OP_STATE);
        buf.writeBoolean(engine.isPlaying());
        buf.writeDouble(engine.positionMs());
        buf.writeFloat(engine.speed());
        send(buf);
    }

    private void sendChartStatus(boolean ok, String reason, String sha1, long lengthMs) {
        PacketByteBuf buf = frame(CharterAudioChannel.OP_CHART_STATUS);
        buf.writeBoolean(ok);
        writeUtf8(buf, reason);
        writeUtf8(buf, sha1);
        buf.writeLong(lengthMs);
        send(buf);
    }

    // ---- 接收 ----

    private void handleServerPacket(PacketByteBuf payload) {
        try {
            byte[] bytes = new byte[payload.readableBytes()];
            payload.readBytes(bytes);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int opcode = in.readInt();
            String sid = readUtf8(in);
            switch (opcode) {
                case CharterAudioChannel.OP_HELLO_ACK -> {
                    boolean ok = in.readBoolean();
                    serverVersion = readUtf8(in);
                    sessionId = readUtf8(in);
                    handshakeOk = ok;
                }
                case CharterAudioChannel.OP_CHART_META -> handleChartMeta(in);
                case CharterAudioChannel.OP_TRANSPORT_PLAY -> {
                    if (!sessionValid(sid)) return;
                    long fromMs = (long) in.readDouble();
                    float speed = in.readFloat();
                    engine.play(fromMs, speed);
                }
                case CharterAudioChannel.OP_TRANSPORT_PAUSE -> {
                    if (!sessionValid(sid)) return;
                    engine.pause();
                }
                case CharterAudioChannel.OP_TRANSPORT_SEEK -> {
                    if (!sessionValid(sid)) return;
                    engine.seek((long) in.readDouble());
                }
                case CharterAudioChannel.OP_TRANSPORT_STOP -> {
                    if (!sessionValid(sid)) return;
                    engine.stop();
                }
                case CharterAudioChannel.OP_SET_LOOP -> {
                    if (!sessionValid(sid)) return;
                    long aMs = (long) in.readDouble();
                    long bMs = (long) in.readDouble();
                    engine.setLoop(aMs, bMs);
                }
                case CharterAudioChannel.OP_SET_SPEED -> {
                    if (!sessionValid(sid)) return;
                    engine.setSpeed(in.readFloat());
                }
                case CharterAudioChannel.OP_PING -> {
                    if (!sessionValid(sid)) return;
                    long nonce = in.readLong();
                    PacketByteBuf pong = frame(CharterAudioChannel.OP_PONG);
                    pong.writeLong(nonce);
                    send(pong);
                }
                case CharterAudioChannel.OP_ERROR -> {
                    String message = readUtf8(in);
                    MinecraftClient.getInstance().inGameHud.getChatHud()
                            .addMessage(net.minecraft.text.Text.literal(
                                    "§c[Charter] 服务端错误: " + message));
                }
                default -> {
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 坏帧直接丢弃（附录 D）
        }
    }

    private boolean sessionValid(String sid) {
        return handshakeOk && sid != null && !sid.isEmpty() && sid.equals(sessionId);
    }

    private void handleChartMeta(DataInputStream in) throws IOException {
        String songFolder = readUtf8(in);
        String songName = readUtf8(in);
        String audioHint = readUtf8(in);
        if (!handshakeOk) {
            return; // 未完成握手不处理会话事务
        }
        AudioLibrary.Match match = AudioLibrary.resolve(songFolder, songName, audioHint);
        if (match.file() == null) {
            sendChartStatus(false, match.reason(), "", 0);
            return;
        }
        boolean loaded = engine.load(match.file(), match.sha1());
        if (loaded) {
            sendChartStatus(true, "", match.sha1(), engine.lengthMs());
        } else {
            sendChartStatus(false, "音频解码失败", "", 0);
        }
    }

    // ---- 编码（与插件端 CharterAudioBridge 一致） ----

    private static void writeUtf8(PacketByteBuf buf, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readUtf8(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len <= 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
