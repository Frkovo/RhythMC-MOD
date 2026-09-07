package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

/**
 * rhythmc:charter_audio 通道常量与 opcode（服务端 = RhythMC-Charter-V2 CharterAudioBridge）。
 * 帧 = [int opcode][string sessionId][payload]；字符串 = int byteLength + UTF-8；大端固定宽度。
 * 与 chart_preview 契约刻意同风格（int 前缀 opcode；禁用 writeUTF/varint）。
 */
public final class CharterAudioChannel {
    public static final String CHANNEL = "rhythmc:charter_audio";
    public static final int PROTOCOL_VERSION = 1;

    // 能力位（§11.2）
    public static final int CAP_SEEK = 1;
    public static final int CAP_LOOP = 2;
    public static final int CAP_SPEED = 4;

    // C→S
    public static final int OP_HELLO = 1;
    // S→C
    public static final int OP_CHART_META = 2;
    public static final int OP_TRANSPORT_PLAY = 3;
    public static final int OP_TRANSPORT_PAUSE = 4;
    public static final int OP_TRANSPORT_SEEK = 5;
    public static final int OP_TRANSPORT_STOP = 6;
    public static final int OP_SET_LOOP = 7;
    public static final int OP_SET_SPEED = 8;
    public static final int OP_PING = 9;
    // S→C ack
    public static final int OP_HELLO_ACK = 101;
    // C→S
    public static final int OP_CHART_STATUS = 102;
    public static final int OP_STATE = 103;
    public static final int OP_PONG = 104;
    public static final int OP_ERROR = 105;

    private CharterAudioChannel() {
    }
}
