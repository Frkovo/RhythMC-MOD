package cn.frkovo.rhythmcv2.rhythmcMod.client.timeline;

import cn.frkovo.rhythmcv2.rhythmcMod.client.net.ChartMetaState;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间轴网格换算：BPM 分段 ⇒ beat→ms（与插件 TimingManager 同口径，含 offset）。
 * 纯逻辑，无渲染依赖。
 */
public final class TimelineGrid {

    public record Segment(double startBeat, double startMs, double msPerBeat, double endBeat) {
    }

    private final double offsetMs;
    private final long lengthMs;
    private final List<Segment> segments;
    private final List<ChartMetaState.Subdivision> subdivisions;

    public TimelineGrid(ChartMetaState meta) {
        this.offsetMs = meta.offsetMs();
        this.lengthMs = Math.max(0L, meta.lengthMs());
        this.subdivisions = List.copyOf(meta.subdivisions());
        List<Segment> built = new ArrayList<>(meta.bpms().size());
        double accMs = 0;
        for (ChartMetaState.Bpm cur : meta.bpms()) {
            if (!Double.isFinite(cur.beat()) || !Double.isFinite(cur.bpm()) || !(cur.bpm() > 0)) {
                continue;
            }
            if (!built.isEmpty()) {
                Segment prev = built.get(built.size() - 1);
                accMs += (cur.beat() - prev.startBeat()) * prev.msPerBeat();
            }
            built.add(new Segment(cur.beat(), accMs, 60000.0 / cur.bpm(), Double.POSITIVE_INFINITY));
        }
        for (int i = 0; i < built.size(); i++) {
            double end = i + 1 < built.size() ? built.get(i + 1).startBeat() : Double.POSITIVE_INFINITY;
            Segment seg = built.get(i);
            built.set(i, new Segment(seg.startBeat(), seg.startMs(), seg.msPerBeat(), end));
        }
        this.segments = List.copyOf(built);
    }

    public boolean isUsable() {
        return !segments.isEmpty() && lengthMs > 0;
    }

    public List<ChartMetaState.Subdivision> subdivisions() {
        return subdivisions;
    }

    /**
     * 相位 A 的分音线 beat（=[fromBeat, toBeat] ∩ 段区间，step = 4/noteValue）；
     * {@code minStepBeats} 用于密度裁剪（低于则不输出该段）。
     */
    public void appendSubdivisionBeats(double fromBeat, double toBeat, List<Double> out, double minStepBeats) {
        int guard = 0;
        for (int i = 0; i < subdivisions.size(); i++) {
            ChartMetaState.Subdivision seg = subdivisions.get(i);
            if (seg.noteValue() <= 0) {
                continue;
            }
            double step = 4d / seg.noteValue();
            if (step < minStepBeats) {
                continue;
            }
            double segEnd = i + 1 < subdivisions.size()
                    ? subdivisions.get(i + 1).startBeat()
                    : Double.POSITIVE_INFINITY;
            double lo = Math.max(fromBeat, seg.startBeat());
            double hi = Math.min(toBeat, segEnd);
            if (hi < lo - 1.0E-9) {
                continue;
            }
            double k = Math.ceil((lo - seg.startBeat()) / step - 1.0E-9);
            for (double b = seg.startBeat() + k * step; b <= hi + 1.0E-9; b += step) {
                out.add(b);
                if (++guard > 20000) {
                    return;
                }
            }
        }
    }

    public long lengthMs() {
        return lengthMs;
    }

    /** beat → 音频毫秒（含 offset；beat 早于首段时按首段外推）。 */
    public double msAtBeat(double beat) {
        if (segments.isEmpty()) {
            return offsetMs;
        }
        Segment seg = segments.get(segments.size() - 1);
        for (Segment candidate : segments) {
            if (beat < candidate.endBeat()) {
                seg = candidate;
                break;
            }
        }
        return offsetMs + seg.startMs() + (beat - seg.startBeat()) * seg.msPerBeat();
    }

    /** 毫秒 → 音频归一化位置 [0,1]。 */
    public double fractionForMs(double ms) {
        return lengthMs <= 0 ? 0 : Math.max(0, Math.min(1, ms / (double) lengthMs));
    }

    /** 音频毫秒 → beat（与 {@link #msAtBeat} 互逆；超出范围时按边界段外推）。 */
    public double beatAtMs(double ms) {
        if (segments.isEmpty()) {
            return 0d;
        }
        Segment chosen = segments.get(segments.size() - 1);
        for (int i = 0; i < segments.size(); i++) {
            double startAbs = offsetMs + segments.get(i).startMs();
            double endAbs = i + 1 < segments.size()
                    ? offsetMs + segments.get(i + 1).startMs()
                    : Double.POSITIVE_INFINITY;
            if (ms >= startAbs && ms < endAbs) {
                chosen = segments.get(i);
                break;
            }
            if (ms < startAbs) {
                chosen = segments.get(i);
                break;
            }
        }
        return chosen.startBeat() + (ms - (offsetMs + chosen.startMs())) / chosen.msPerBeat();
    }

    /** 谱面长度覆盖到的最后一个整数拍（上限保护，避免异常数据死循环）。 */
    public int lastBeat() {
        int beat = 0;
        while (beat < 200_000) {
            if (msAtBeat(beat + 1) > lengthMs) {
                break;
            }
            beat++;
        }
        return beat;
    }
}
