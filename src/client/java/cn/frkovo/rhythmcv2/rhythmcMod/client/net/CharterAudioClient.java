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
    private final ChartMetaState chartMeta = new ChartMetaState();
    private final ChartNotesState chartNotes = new ChartNotesState();
    private final ViewState viewState = new ViewState();
    private final EditState editState = new EditState();
    private final AtomicInteger tickCounter = new AtomicInteger();
    /** APPLY 节流：拖动中每 tick（≤50ms）最多发一帧，松手/关闭时补发最终值。 */
    private volatile EditState.Snapshot pendingApply;

    private volatile boolean handshakeOk;
    private volatile boolean protocolMismatch;
    private volatile String serverVersion = "";
    private volatile boolean registered;
    private volatile Path loadedFile;
    /** 下载完成后的后台解码线程（波形不必等播放）。 */
    private volatile Thread loadingThread;
    private final AtomicInteger loadGeneration = new AtomicInteger();

    public static CharterAudioClient get() {
        return INSTANCE;
    }

    public boolean isHandshakeOk() {
        return handshakeOk;
    }

    public CharterAudioEngine engine() {
        return engine;
    }

    public ChartMetaState chartMeta() {
        return chartMeta;
    }

    public ChartNotesState chartNotes() {
        return chartNotes;
    }

    public ViewState viewState() {
        return viewState;
    }

    public EditState editState() {
        return editState;
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
            // STATE(103)：播放状态/位置周期上报，供插件对账
            if (handshakeOk && tick % 5 == 0) {
                sendState();
            }
            // APPLY 节流：拖动中每 tick 最多一帧
            flushNoteEdit();
        });
    }

    private void resetSession() {
        handshakeOk = false;
        protocolMismatch = false;
        serverVersion = "";
        loadedFile = null;
        loadGeneration.incrementAndGet();
        loadingThread = null;
        engine.stop();
        receiver.reset();
        chartMeta.reset();
        chartNotes.reset();
        viewState.reset();
        editState.reset();
        pendingApply = null;
    }

    /** 下载校验通过后立刻后台解码 + 预构建波形（波形不必等播放）。 */
    private void ensureAudioLoadedAsync() {
        Path file = receiver.completedFile();
        if (file == null || file.equals(loadedFile)) {
            return;
        }
        Thread current = loadingThread;
        if (current != null && current.isAlive()) {
            return;
        }
        String sha = receiver.completedSha256();
        int generation = loadGeneration.get();
        Thread thread = new Thread(() -> {
            if (engine.load(file, sha) && generation == loadGeneration.get()) {
                loadedFile = file;
                engine.prepareWaveform();
                LOGGER.info("[charter_audio] audio preloaded (waveform ready): {}", file);
            }
        }, "CharterAudio-Preload");
        thread.setDaemon(true);
        loadingThread = thread;
        thread.start();
    }

    /** 播放前确保解码完成：等待后台预载，必要时同步补载。 */
    private boolean awaitAudioLoaded() {
        Path file = receiver.completedFile();
        if (file == null) {
            return false;
        }
        if (file.equals(loadedFile) && engine.isLoaded()) {
            return true;
        }
        Thread current = loadingThread;
        if (current != null && current.isAlive()) {
            try {
                current.join(8000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (file.equals(loadedFile) && engine.isLoaded()) {
            return true;
        }
        if (!engine.load(file, receiver.completedSha256())) {
            return false;
        }
        loadedFile = file;
        engine.prepareWaveform();
        return true;
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

    /** STATE(103)：byte playing, double positionMs, float speed, double lengthMs（音频时长）。 */
    public void sendState() {
        if (!handshakeOk) {
            return;
        }
        PacketByteBuf buf = frame(CharterAudioChannel.OP_STATE);
        buf.writeByte(engine.isPlaying() ? 1 : 0);
        buf.writeDouble(engine.positionMs());
        buf.writeFloat(engine.speed());
        buf.writeDouble(engine.lengthMs());
        send(buf);
    }

    /** TRANSPORT_REQ(10)：mod 键位请求（由插件执行）。 */
    public void requestTransport(int action, int bars) {
        if (!handshakeOk) {
            return;
        }
        PacketByteBuf buf = frame(CharterAudioChannel.OP_TRANSPORT_REQ);
        buf.writeByte(action);
        buf.writeInt(bars);
        send(buf);
    }

    /** VIEW_ZOOM(110)：世界网格缩放 ±1 级（SHIFT+滚轮）。 */
    public void requestViewZoom(int direction) {
        if (!handshakeOk) {
            return;
        }
        PacketByteBuf buf = frame(CharterAudioChannel.OP_VIEW_ZOOM);
        buf.writeByte(direction);
        send(buf);
    }

    /** VIEW_SEEK(112)：时间轴点击/拖动 seek（不适用对账，插件走正常 seek 路径）。 */
    public void requestViewSeek(double toMs) {
        if (!handshakeOk) {
            return;
        }
        PacketByteBuf buf = frame(CharterAudioChannel.OP_VIEW_SEEK);
        buf.writeDouble(Math.max(0d, toMs));
        send(buf);
    }

    /** EDIT_REQ(113)：编辑器动作（中键选中/撤销/重做/取消选中/删除/复制）。 */
    public void requestEdit(int action) {
        LOGGER.info("[charter_audio] EDIT_REQ action={}", action);
        if (!handshakeOk) {
            return;
        }
        PacketByteBuf buf = frame(CharterAudioChannel.OP_EDIT_REQ);
        buf.writeByte(action);
        send(buf);
    }

    /** EDIT_REQ(113) APPLY：把面板上的属性推给插件（节流，见 {@link #flushNoteEdit()}）。 */
    public void applyNoteEdit(EditState.Snapshot properties) {
        if (!handshakeOk) {
            return;
        }
        pendingApply = properties;
    }

    /** 立即补发最后一次 APPLY（拖动松手 / 关闭面板时调用）。 */
    public void flushNoteEdit() {
        EditState.Snapshot pending = pendingApply;
        if (pending == null || !handshakeOk) {
            return;
        }
        pendingApply = null;
        PacketByteBuf buf = frame(CharterAudioChannel.OP_EDIT_REQ);
        buf.writeByte(CharterAudioChannel.EDIT_APPLY);
        buf.writeByte(pending.type());
        buf.writeDouble(pending.beat());
        buf.writeDouble(pending.posX());
        buf.writeDouble(pending.posY());
        buf.writeDouble(pending.posZ());
        buf.writeFloat(pending.scaleX());
        buf.writeFloat(pending.scaleY());
        buf.writeFloat(pending.scaleZ());
        buf.writeFloat(pending.rotX());
        buf.writeFloat(pending.rotY());
        buf.writeFloat(pending.rotZ());
        buf.writeByte(pending.holdBoundary());
        buf.writeInt(pending.holdGroupManual());
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
                case CharterAudioChannel.OP_CHART_META -> handleChartMeta(in);
                case CharterAudioChannel.OP_CHART_NOTES -> handleChartNotes(in);
                case CharterAudioChannel.OP_VIEW_STATE -> handleViewState(in);
                case CharterAudioChannel.OP_EDIT_STATE -> handleEditState(in);
                case CharterAudioChannel.OP_AUDIO_PUSH_START -> handlePushStart(in);
                case CharterAudioChannel.OP_AUDIO_PUSH_CHUNK -> handlePushChunk(in);
                case CharterAudioChannel.OP_AUDIO_PUSH_END -> handlePushEnd(in);
                case CharterAudioChannel.OP_TRANSPORT_PLAY -> {
                    if (!handshakeOk) return;
                    long fromMs = (long) in.readDouble();
                    float speed = in.readFloat();
                    playFromServer(fromMs, speed);
                    sendState();
                }
                case CharterAudioChannel.OP_TRANSPORT_PAUSE -> {
                    if (handshakeOk) {
                        engine.pause();
                        sendState();
                    }
                }
                case CharterAudioChannel.OP_TRANSPORT_SEEK -> {
                    if (handshakeOk) {
                        engine.seek((long) in.readDouble());
                        sendState();
                    }
                }
                case CharterAudioChannel.OP_TRANSPORT_STOP -> {
                    if (handshakeOk) {
                        engine.stop();
                        sendState();
                    }
                }
                case CharterAudioChannel.OP_SET_LOOP -> {
                    if (handshakeOk) {
                        engine.setLoop((long) in.readDouble(), (long) in.readDouble());
                        sendState();
                    }
                }
                case CharterAudioChannel.OP_SET_SPEED -> {
                    if (handshakeOk) {
                        engine.setSpeed(in.readFloat());
                        sendState();
                    }
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

    private void handleChartMeta(DataInputStream in) throws IOException {
        if (!handshakeOk) return;
        String songName = readUtf8(in);
        long lengthMs = in.readLong();
        long offsetMs = in.readLong();
        int bpmCount = in.readInt();
        if (bpmCount < 0 || bpmCount > 4096) {
            return;
        }
        java.util.List<ChartMetaState.Bpm> bpms = new java.util.ArrayList<>(bpmCount);
        for (int i = 0; i < bpmCount; i++) {
            bpms.add(new ChartMetaState.Bpm(in.readDouble(), in.readDouble()));
        }
        int subdivCount = in.readInt();
        if (subdivCount < 0 || subdivCount > 4096) {
            return;
        }
        java.util.List<ChartMetaState.Subdivision> subdivisions = new java.util.ArrayList<>(subdivCount);
        for (int i = 0; i < subdivCount; i++) {
            double startBeat = in.readDouble();
            int noteValue = in.readInt();
            if (!Double.isFinite(startBeat) || startBeat < 0 || noteValue <= 0) {
                return;
            }
            subdivisions.add(new ChartMetaState.Subdivision(startBeat, noteValue));
        }
        chartMeta.update(songName, lengthMs, offsetMs, bpms, subdivisions);
        LOGGER.info("[charter_audio] chart meta: {} length={}ms bpms={} subdivs={}",
                songName, lengthMs, bpmCount, subdivCount);
    }

    /** CHART_NOTES(109)：int count + {double beat, byte type}[]；坏快照整包丢弃。 */
    private void handleChartNotes(DataInputStream in) throws IOException {
        if (!handshakeOk) {
            return;
        }
        int count = in.readInt();
        if (count < 0 || count > ChartNotesState.MAX_NOTES) {
            return;
        }
        java.util.List<ChartNotesState.NoteMarker> notes = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double beat = in.readDouble();
            int type = in.readByte() & 0xFF;
            if (!Double.isFinite(beat) || beat < 0 || type > 3) {
                return;
            }
            notes.add(new ChartNotesState.NoteMarker(beat, type));
        }
        chartNotes.update(notes);
        LOGGER.info("[charter_audio] chart notes: {} markers", count);
    }

    /** VIEW_STATE(111)：int zoomIndex, double barBlocks, int levelCount, double cursorMs。 */
    private void handleViewState(DataInputStream in) throws IOException {
        if (!handshakeOk) {
            return;
        }
        int zoomIndex = in.readInt();
        double barBlocks = in.readDouble();
        int levelCount = in.readInt();
        double cursorMs = in.readDouble();
        if (levelCount < 1 || levelCount > 64 || !(barBlocks > 0) || !Double.isFinite(barBlocks)) {
            return;
        }
        viewState.update(Math.max(0, zoomIndex), barBlocks, levelCount, Math.max(0d, cursorMs));
    }

    /** EDIT_STATE(114)：选中音符属性快照（ok=false = 无选中/已关闭）。 */
    private void handleEditState(DataInputStream in) throws IOException {
        if (!handshakeOk) {
            return;
        }
        boolean ok = in.readBoolean();
        String reason = readUtf8(in);
        int type = in.readByte() & 0xFF;
        double beat = in.readDouble();
        double posX = in.readDouble();
        double posY = in.readDouble();
        double posZ = in.readDouble();
        float scaleX = in.readFloat();
        float scaleY = in.readFloat();
        float scaleZ = in.readFloat();
        float rotX = in.readFloat();
        float rotY = in.readFloat();
        float rotZ = in.readFloat();
        int holdGroup = in.readInt();
        int holdGroupSize = in.readInt();
        int holdGroupIndex = in.readInt();
        int holdBoundary = in.readByte();
        int holdGroupManual = in.readInt();
        double maxHalfWidth = in.readDouble();
        double maxHalfHeight = in.readDouble();
        double dodgeScale = in.readDouble();
        double beatStep = in.readDouble();
        boolean canUndo = in.readBoolean();
        boolean canRedo = in.readBoolean();
        if (ok && (type > 3 || !Double.isFinite(beat) || beat < 0
                || !(maxHalfWidth > 0) || !(maxHalfHeight > 0) || !(beatStep > 0))) {
            return;
        }
        editState.update(new EditState.Snapshot(ok, reason == null ? "" : reason, ok ? type : 0,
                ok ? beat : 0d, posX, posY, posZ,
                scaleX, scaleY, scaleZ, rotX, rotY, rotZ,
                holdGroup, holdGroupSize, holdGroupIndex, holdBoundary, holdGroupManual,
                maxHalfWidth > 0 ? maxHalfWidth : 2.5d,
                maxHalfHeight > 0 ? maxHalfHeight : 3.0d,
                dodgeScale > 0 ? dodgeScale : 1.5d,
                beatStep > 0 ? beatStep : 0.25d,
                canUndo, canRedo));
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
            ensureAudioLoadedAsync();
        }
    }

    private void playFromServer(long fromMs, float speed) {
        if (!awaitAudioLoaded()) {
            sendError(receiver.completedFile() == null
                    ? "音频尚未下载完成"
                    : "音频解码失败（不支持的格式或文件损坏）");
            return;
        }
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
