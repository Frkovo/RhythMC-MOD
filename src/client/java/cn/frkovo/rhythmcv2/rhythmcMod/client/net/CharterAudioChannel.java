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
    /** v1.1: mod keybind transport request (actions routed through the plugin, never local). */
    public static final int OP_TRANSPORT_REQ = 10;

    // TRANSPORT_REQ action（与插件 CharterAudioBridge 严格一致）
    public static final int REQ_TOGGLE_PLAY = 0;
    public static final int REQ_PREV_BAR = 1;
    public static final int REQ_NEXT_BAR = 2;
    public static final int REQ_LOOP_A = 3;
    public static final int REQ_LOOP_B = 4;
    public static final int REQ_LOOP_CLEAR = 5;
    public static final int REQ_STOP = 6;

    // M0 音频推送（服务端→客户端，分块）
    public static final int OP_AUDIO_PUSH_START = 106; // S→C
    public static final int OP_AUDIO_PUSH_CHUNK = 107; // S→C
    public static final int OP_AUDIO_PUSH_END = 108;   // S→C
    public static final int OP_AUDIO_PUSH_ACK = 11;    // C→S

    // S→C ack
    public static final int OP_HELLO_ACK = 101;
    // C→S
    public static final int OP_CHART_STATUS = 102;
    public static final int OP_STATE = 103;
    public static final int OP_PONG = 104;
    public static final int OP_ERROR = 105;
    /** v1.1: 谱面音符快照（时间轴 HUD）：int count + {double beat, byte type}[]。 */
    public static final int OP_CHART_NOTES = 109;
    /** v1.1: 世界网格缩放请求（C→S）：byte direction（+1 更细/更大格，-1 更粗）。 */
    public static final int OP_VIEW_ZOOM = 110;
    /** v1.1: 视图状态（S→C）：int zoomIndex, double barBlocks, int levelCount, double cursorMs。 */
    public static final int OP_VIEW_STATE = 111;
    /** v1.1: 时间轴点击 seek 请求（C→S）：double toMs。 */
    public static final int OP_VIEW_SEEK = 112;
    /** v1.2: 编辑器请求（C→S）：byte action [+ APPLY 载荷]。 */
    public static final int OP_EDIT_REQ = 113;
    /** v1.2: 编辑状态（S→C）：选中音符属性快照 + undo/redo 可用性。 */
    public static final int OP_EDIT_STATE = 114;

    // EDIT_REQ action（与插件 CharterAudioBridge 严格一致）
    public static final int EDIT_OPEN_NOTE_GUI = 0;
    public static final int EDIT_UNDO = 1;
    public static final int EDIT_REDO = 2;
    public static final int EDIT_DESELECT = 3;
    public static final int EDIT_APPLY = 4;
    public static final int EDIT_DELETE_SELECTED = 5;
    public static final int EDIT_CLONE_TO_NEXT = 6;

    private CharterAudioChannel() {
    }
}
