package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

import java.util.List;

/**
 * EVENT_HUD(117) 缓存：时间轴 HUD 上要画的**当前通道事件曲线**。
 *
 * <p>与 {@link EventState} 的区别：没有面板标志（面板开关由 EVENT_STATE 驱动），
 * easing 用 `byte`；只在 mod 就绪 / 切 Track / 切通道 / 事件增删改 / undo-redo 时下发。</p>
 */
public final class EventHudState {

    /** 单通道事件段上限（防御异常帧）。 */
    private static final int MAX_EVENTS = 4096;

    /** 段：起止拍 + 起止值 + 缓动序号。 */
    public record Segment(double startBeat, double endBeat, double startValue, double endValue, int easing) {

        public boolean jump() {
            return Math.abs(endBeat - startBeat) <= 1.0E-6d;
        }
    }

    /** 一次推送的快照；{@code trackId < 0} 表示无数据（HUD 不画曲线）。 */
    public record Snapshot(int trackId, int channel, List<Segment> segments) {

        public static Snapshot empty() {
            return new Snapshot(-1, 0, List.of());
        }
    }

    private volatile Snapshot snapshot = Snapshot.empty();

    public Snapshot snapshot() {
        return snapshot;
    }

    public void update(Snapshot newSnapshot) {
        this.snapshot = newSnapshot == null ? Snapshot.empty() : newSnapshot;
    }

    public void reset() {
        snapshot = Snapshot.empty();
    }

    public static int maxEvents() {
        return MAX_EVENTS;
    }
}
