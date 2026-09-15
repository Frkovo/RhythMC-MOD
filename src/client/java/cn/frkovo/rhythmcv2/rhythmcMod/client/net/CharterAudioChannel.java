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
    /** v1.3: 编辑状态（S→C）：选中音符属性快照 + undo/redo 可用性。 */
    public static final int OP_EDIT_STATE = 114;

    // EDIT_REQ action（与插件 CharterAudioBridge 严格一致）
    public static final int EDIT_OPEN_NOTE_GUI = 0;
    public static final int EDIT_UNDO = 1;
    public static final int EDIT_REDO = 2;
    public static final int EDIT_DESELECT = 3;
    public static final int EDIT_APPLY = 4;
    public static final int EDIT_DELETE_SELECTED = 5;
    public static final int EDIT_CLONE_TO_NEXT = 6;

    /** v1.3: Track 事件请求（C→S）：byte op [+ 载荷]。 */
    public static final int OP_EVENT_REQ = 115;
    /** v1.3: Track 事件状态（S→C）：某 Track 某通道的事件段列表 + 真实速度预览开关。 */
    public static final int OP_EVENT_STATE = 116;
    /** v1.3: 事件曲线（S→C）：时间轴 HUD 的当前通道曲线（不塞进每 tick 的 VIEW_STATE）。 */
    public static final int OP_EVENT_HUD = 117;

    // EVENT_REQ op（与插件 CharterAudioBridge 严格一致）
    public static final int EVENT_LIST = 0;
    /** 在某一拍切开该通道（1 段 → 2 段）；载荷 `byte channel, double beat`。 */
    public static final int EVENT_SPLIT = 1;
    public static final int EVENT_UPDATE = 2;
    public static final int EVENT_REMOVE = 3;
    public static final int EVENT_CLEAR = 4;
    public static final int EVENT_PREVIEW = 5;
    public static final int EVENT_CLOSE = 6;
    /** 试听某段（index >= 0）或从游标播放（index < 0）：`byte channel, int index`。 */
    public static final int EVENT_AUDITION = 7;

    // EVENT_UPDATE 的 valueMode（与插件 EventRules / EditorTrack.updateEvent 一致）
    /** 起止值都设（不额外同步）。 */
    public static final int EVENT_VALUE_BOTH = 0;
    /** 只设终点值，并把下一段起点值同步为同一值（默认连续）。 */
    public static final int EVENT_VALUE_END = 1;
    /** 只设起点值（与前面不连续 → 跳变）。 */
    public static final int EVENT_VALUE_START = 2;

    private CharterAudioChannel() {
    }
}
