package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

import java.util.List;

/**
 * EVENT_STATE(116) 缓存：当前 Track 某通道的事件段列表 + 真实速度预览开关。
 * {@code ok=false} 表示面板应关闭（无会话 / 播放中 / 会话已关闭）。
 */
public final class EventState {

    /** 通道编号（与插件 EventChannel 顺序严格一致）。 */
    public static final int CHANNEL_SPEED = 0;
    public static final int CHANNEL_X = 1;
    public static final int CHANNEL_Y = 2;
    public static final int CHANNEL_Z = 3;
    public static final int CHANNEL_SCALE_X = 4;
    public static final int CHANNEL_SCALE_Y = 5;
    public static final int CHANNEL_SCALE_Z = 6;
    public static final int CHANNEL_ROT_X = 7;
    public static final int CHANNEL_ROT_Y = 8;
    public static final int CHANNEL_ROT_Z = 9;

    /** 通道键名（与插件 EventChannel.key() 一致）。 */
    public static final String[] CHANNEL_KEYS = {
            "speed", "x", "y", "z", "scalex", "scaley", "scalez", "rotx", "roty", "rotz"};

    /** 通道显示名（面板按钮）。 */
    public static final String[] CHANNEL_LABELS = {
            "Speed", "X", "Y", "Z", "SX", "SY", "SZ", "RX", "RY", "RZ"};

    public static final int CHANNEL_COUNT = CHANNEL_KEYS.length;

    /** 单个事件段；{@code jump()} 为真表示零长度跳变（只取 endValue）。 */
    public record Event(double startBeat, double endBeat, double startValue, double endValue, int easing) {

        private static final double EPSILON = 1.0E-6;

        public boolean jump() {
            return Math.abs(endBeat - startBeat) <= EPSILON;
        }

        public Event withStart(double beat) {
            return new Event(beat, endBeat, startValue, endValue, easing);
        }

        public Event withEnd(double beat) {
            return new Event(startBeat, beat, startValue, endValue, easing);
        }

        public Event withStartValue(double value) {
            return new Event(startBeat, endBeat, value, endValue, easing);
        }

        public Event withEndValue(double value) {
            return new Event(startBeat, endBeat, startValue, value, easing);
        }

        public Event withEasing(int easing) {
            return new Event(startBeat, endBeat, startValue, endValue, easing);
        }

        public Event asJump() {
            return new Event(startBeat, startBeat, endValue, endValue, Easing.LINEAR);
        }

        public Event asSegment(double length) {
            return new Event(startBeat, startBeat + length, startValue, endValue, easing);
        }
    }

    public record Snapshot(boolean ok, String reason, int trackId, int channel, boolean preview,
                           boolean panel, int selectIndex, java.util.List<Event> events) {

        public Event event(int index) {
            return index >= 0 && index < events.size() ? events.get(index) : null;
        }
    }

    private volatile Snapshot snapshot = closed();

    public static Snapshot closed() {
        return new Snapshot(false, "", 0, CHANNEL_SPEED, false, false, -1, java.util.List.of());
    }

    public void update(Snapshot snapshot) {
        this.snapshot = snapshot == null ? closed() : snapshot;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public boolean open() {
        return snapshot.ok();
    }

    public void reset() {
        snapshot = closed();
    }
}
