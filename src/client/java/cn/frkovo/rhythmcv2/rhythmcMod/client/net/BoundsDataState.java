package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

import java.util.List;

/**
 * `BOUNDS_DATA(119)` 缓存：判定面基点 + 每条 Track 的 10 条事件通道曲线（**静态**）。
 *
 * <p>开关打开时 / 谱面数据变化时由插件下发一次；轮廓渲染器每帧用
 * {@link #evaluate} 按当前播放头在本地求值，因此 60fps 平滑且不占带宽。</p>
 *
 * <p>通道顺序与插件 {@code EventChannel} 枚举一致：
 * 0 Speed / 1 X / 2 Y / 3 Z / 4 SX / 5 SY / 6 SZ / 7 RX / 8 RY / 9 RZ。
 * 几何只用 1,2,3（位移）、4,5,6（缩放）、7,8,9（旋转）；Speed 不参与。</p>
 *
 * <p>几何参数（判定面半宽 {@code planeHalf}、盒长 {@code boxLength}、垂直偏移 {@code yOffset}）
 * 也由插件下发（配置热调，不需要改 mod）。</p>
 */
public final class BoundsDataState {

    /** 单通道段数上限（坏帧防护）。 */
    public static final int MAX_SEGMENTS_PER_CHANNEL = 8192;
    /** 每条 Track 固定 10 条通道。 */
    public static final int CHANNEL_COUNT = 10;

    /** 事件段（与插件 {@code NumEvent} 同构）。 */
    public record Segment(double startBeat, double endBeat, double startValue, double endValue, int easing) {
    }

    /** 单 Track：id + 10 条通道（顺序固定）。 */
    public record Track(int trackId, List<List<Segment>> channels) {
    }

    private volatile boolean on;
    private volatile double baseX;
    private volatile double baseY;
    private volatile double baseZ;
    private volatile double planeHalf = 2.5d;
    private volatile double boxLength = 25d;
    private volatile double yOffset = 1d;
    private volatile List<Track> tracks = List.of();

    public void update(boolean on, double baseX, double baseY, double baseZ,
                       double planeHalf, double boxLength, double yOffset, List<Track> tracks) {
        this.on = on;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        if (planeHalf > 0d) {
            this.planeHalf = planeHalf;
        }
        if (boxLength > 0d) {
            this.boxLength = boxLength;
        }
        if (Double.isFinite(yOffset)) {
            this.yOffset = yOffset;
        }
        this.tracks = tracks == null ? List.of() : List.copyOf(tracks);
    }

    public void reset() {
        update(false, 0d, 0d, 0d, 2.5d, 25d, 1d, List.of());
    }

    public boolean on() {
        return on;
    }

    public double baseX() {
        return baseX;
    }

    public double baseY() {
        return baseY;
    }

    public double baseZ() {
        return baseZ;
    }

    public double planeHalf() {
        return planeHalf;
    }

    public double boxLength() {
        return boxLength;
    }

    /** 判定面/轮廓相对基点的垂直偏移（格）。 */
    public double yOffset() {
        return yOffset;
    }

    public List<Track> tracks() {
        return tracks;
    }

    /** 某条通道（越界返回空列表）。 */
    public static List<Segment> channel(Track track, int index) {
        if (index < 0 || index >= track.channels().size()) {
            return List.of();
        }
        List<Segment> segments = track.channels().get(index);
        return segments == null ? List.of() : segments;
    }

    /**
     * 通道取值：与插件 {@code ChartUtils.getTransformation(events, beat, default)} 同语义
     * （段按 startBeat 升序；拍落在段内则按该段 easing 插值；已过段取终点值）。
     */
    public static double evaluate(List<Segment> events, double beat, double defaultValue) {
        double value = defaultValue;
        for (Segment ev : events) {
            if (ev.startBeat() > beat) {
                break;
            }
            if (beat >= ev.endBeat()) {
                value = ev.endValue();
                continue;
            }
            double t = (beat - ev.startBeat()) / (ev.endBeat() - ev.startBeat());
            value = EasingCurve.ease(ev.startValue(), ev.endValue(), t, ev.easing());
            break;
        }
        return value;
    }
}
