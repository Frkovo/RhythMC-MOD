package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

import cn.frkovo.rhythmcv2.rhythmcMod.client.audio.AudioTransferReceiver;
import cn.frkovo.rhythmcv2.rhythmcMod.client.audio.CharterAudioEngine;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * charter_audio 客户端端（M0）：握手 HELLO/HELLO_ACK、服务端→客户端音频分块推送接收、
 * Transport 指令消费。M0 会话为空串，双方不做 sessionId 过滤。
 */
public final class CharterAudioClient {

    private static final CharterAudioClient INSTANCE = new CharterAudioClient();
    private static final String MOD_VERSION = "1.0.0";
    private static final Logger LOGGER = LoggerFactory.getLogger("RhythMC-Charter");

    private final CharterAudioEngine engine = new CharterAudioEngine();
    private final AudioTransferReceiver receiver = new AudioTransferReceiver();
    private final AtomicInteger tickCounter = new AtomicInteger();

    private volatile boolean handshakeOk;
    private volatile boolean protocolMismatch;
    private volatile String serverVersion = "";
    private volatile boolean registered;
    private Path loadedFile;

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
            resetSession();
            // 延迟握手，等服务端插件消息通道就绪
            Thread helloThread = new Thread(() -> {
                sleep(1000);
                if (MinecraftClient.getInstance().getNetworkHandler() != null) {
                    sendHello();
                }
            }, "CharterAudio-Hello");
            helloThread.setDaemon(true);
            helloThread.start();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetSession());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            int tick = tickCounter.incrementAndGet();
            // 握手未完成时每 100 tick 补发 HELLO（首包可能早于服务端通道就绪）
            if (tick % 100 == 0 && !protocolMismatch && !handshakeOk
                    && MinecraftClient.getInstance().getNetworkHandler() != null) {
                sendHello();
            }
        });
    }

    private void resetSession() {
        handshakeOk = false;
        protocolMismatch = false;
        serverVersion = "";
        loadedFile = null;
        engine.stop();
        receiver.reset();
    }

    // ---- 发送 ----

    private PacketByteBuf frame(int opcode) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(opcode);
        writeUtf8(buf, "");
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
        writeUtf8(buf, "");
        buf.writeInt(CharterAudioChannel.PROTOCOL_VERSION);
        writeUtf8(buf, "RhythMC-MOD " + MOD_VERSION);
        buf.writeInt(CharterAudioChannel.CAP_SEEK | CharterAudioChannel.CAP_LOOP
                | CharterAudioChannel.CAP_SPEED);
        send(buf);
    }

    private void sendAck(String transferId, boolean ok, String reason) {
        PacketByteBuf buf = frame(CharterAudioChannel.OP_AUDIO_PUSH_ACK);
        writeUtf8(buf, transferId);
        buf.writeByte(ok ? 1 : 0);
        writeUtf8(buf, reason == null ? "" : reason);
        send(buf);
    }

    private void sendError(String message) {
        PacketByteBuf buf = frame(CharterAudioChannel.OP_ERROR);
        writeUtf8(buf, message);
        send(buf);
    }

    // ---- 接收 ----

    private void handleServerPacket(PacketByteBuf payload) {
        try {
            byte[] bytes = new byte[payload.readableBytes()];
            payload.readBytes(bytes);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int opcode = in.readInt();
            readUtf8(in); // sessionId：M0 忽略
            switch (opcode) {
                case CharterAudioChannel.OP_HELLO_ACK -> handleHelloAck(in);
                case CharterAudioChannel.OP_AUDIO_PUSH_START -> handlePushStart(in);
                case CharterAudioChannel.OP_AUDIO_PUSH_CHUNK -> handlePushChunk(in);
                case CharterAudioChannel.OP_AUDIO_PUSH_END -> handlePushEnd(in);
                case CharterAudioChannel.OP_TRANSPORT_PLAY -> {
                    if (!handshakeOk) return;
                    long fromMs = (long) in.readDouble();
                    float speed = in.readFloat();
                    playFromServer(fromMs, speed);
                }
                case CharterAudioChannel.OP_TRANSPORT_PAUSE -> {
                    if (handshakeOk) engine.pause();
                }
                case CharterAudioChannel.OP_TRANSPORT_SEEK -> {
                    if (handshakeOk) engine.seek((long) in.readDouble());
                }
                case CharterAudioChannel.OP_TRANSPORT_STOP -> {
                    if (handshakeOk) engine.stop();
                }
                case CharterAudioChannel.OP_SET_LOOP -> {
                    if (handshakeOk) engine.setLoop((long) in.readDouble(), (long) in.readDouble());
                }
                case CharterAudioChannel.OP_SET_SPEED -> {
                    if (handshakeOk) engine.setSpeed(in.readFloat());
                }
                case CharterAudioChannel.OP_PING -> {
                    if (!handshakeOk) return;
                    long nonce = in.readLong();
                    PacketByteBuf pong = frame(CharterAudioChannel.OP_PONG);
                    pong.writeLong(nonce);
                    send(pong);
                }
                case CharterAudioChannel.OP_ERROR -> chat("§c[Charter] 服务端错误: " + readUtf8(in));
                default -> {
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 坏帧直接丢弃（附录 D）
        }
    }

    private void handleHelloAck(DataInputStream in) throws IOException {
        boolean ok = in.readBoolean();
        serverVersion = readUtf8(in);
        readUtf8(in); // sessionId：M0 忽略
        handshakeOk = ok;
        protocolMismatch = !ok;
        if (ok) {
            LOGGER.info("[charter_audio] handshake ok: server={}", serverVersion);
        } else {
            LOGGER.warn("[charter_audio] protocol mismatch: server={} — 请更新 Mod/插件到同一版本",
                    serverVersion);
        }
    }

    private void handlePushStart(DataInputStream in) throws IOException {
        if (!handshakeOk) return;
        String transferId = readUtf8(in);
        String songFolder = readUtf8(in);
        String fileName = readUtf8(in);
        long totalBytes = in.readLong();
        String sha256 = readUtf8(in);
        in.readInt(); // chunkSize（接收端不依赖，顺序写盘）
        in.readInt(); // totalChunks
        if (!receiver.start(transferId, songFolder, fileName, totalBytes, sha256)) {
            sendAck(transferId, false, receiver.lastError());
        }
    }

    private void handlePushChunk(DataInputStream in) throws IOException {
        String transferId = readUtf8(in);
        int index = in.readInt();
        int length = in.readInt();
        if (length < 0 || length > 1 << 20) {
            sendAck(transferId, false, "非法块长度");
            receiver.fail("非法块长度");
            return;
        }
        byte[] data = new byte[length];
        in.readFully(data);
        if (!receiver.chunk(transferId, index, data)) {
            sendAck(transferId, false, receiver.lastError());
        }
    }

    private void handlePushEnd(DataInputStream in) throws IOException {
        String transferId = readUtf8(in);
        boolean ok = receiver.end(transferId);
        sendAck(transferId, ok, ok ? "" : receiver.lastError());
        if (ok) {
            chat("§a[Charter] 音乐下载完成（sha256 校验通过）");
            LOGGER.info("[charter_audio] download complete: {}", receiver.completedFile());
        }
    }

    private void playFromServer(long fromMs, float speed) {
        Path file = receiver.completedFile();
        if (file == null) {
            sendError("音频尚未下载完成");
            return;
        }
        if (!file.equals(loadedFile) && !engine.load(file, receiver.completedSha256())) {
            sendError("音频解码失败（不支持的格式或文件损坏）");
            return;
        }
        loadedFile = file;
        engine.play(fromMs, speed);
        LOGGER.info("[charter_audio] play from {}ms @{}x", fromMs, speed);
    }

    private static void chat(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal(message));
        }
    }

    // ---- 编码（与插件端 CharterAudioBridge 一致） ----

    private static void writeUtf8(PacketByteBuf buf, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
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
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
